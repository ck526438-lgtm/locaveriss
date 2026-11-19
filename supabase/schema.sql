-- Locavériss : schéma Supabase. À coller une seule fois dans SQL Editor > Run.

-- ---------- Profils (créés automatiquement à l'inscription) ----------
create table if not exists public.profils (
  id uuid primary key references auth.users (id) on delete cascade,
  nom text not null default '',
  telephone text not null default '',
  type_compte text not null default 'particulier',   -- 'particulier' ou 'agence'
  verifie boolean not null default false,
  created_at timestamptz not null default now()
);

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profils (id, nom, telephone, type_compte)
  values (
    new.id,
    coalesce(new.raw_user_meta_data->>'nom', ''),
    coalesce(new.raw_user_meta_data->>'telephone', ''),
    coalesce(new.raw_user_meta_data->>'type_compte', 'particulier')
  )
  on conflict (id) do nothing;
  return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------- Annonces ----------
create table if not exists public.annonces (
  id uuid primary key default gen_random_uuid(),
  proprietaire uuid not null default auth.uid() references auth.users (id) on delete cascade,
  titre text not null,
  type text not null default 'Studio',
  ville text not null,
  quartier text not null default '',
  loyer integer not null check (loyer > 0),
  caution_mois integer not null default 0,
  avance_mois integer not null default 0,
  pieces integer not null default 0,
  description text not null default '',
  telephone text not null,
  photos text[] not null default '{}',
  disponible boolean not null default true,
  status text not null default 'en_attente'
    check (status in ('en_attente', 'verifiee', 'refusee')),
  motif_refus text,
  created_at timestamptz not null default now()
);

create index if not exists annonces_public_idx
  on public.annonces (status, disponible, created_at desc);
create index if not exists annonces_proprietaire_idx
  on public.annonces (proprietaire);

-- Un propriétaire ne peut jamais valider lui-même son annonce :
-- toute création ou modification du contenu la remet "en_attente".
-- Seul l'administrateur (tableau de bord Supabase) change le statut.
create or replace function public.proteger_annonce()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then
    return new;                       -- tableau de bord / SQL Editor = administrateur
  end if;
  if tg_op = 'INSERT' then
    new.proprietaire := auth.uid();
    new.status := 'en_attente';
    new.motif_refus := null;
  else
    new.proprietaire := old.proprietaire;
    new.motif_refus := old.motif_refus;
    if row(new.titre, new.type, new.ville, new.quartier, new.loyer, new.caution_mois,
           new.avance_mois, new.pieces, new.description, new.telephone, new.photos)
       is distinct from
       row(old.titre, old.type, old.ville, old.quartier, old.loyer, old.caution_mois,
           old.avance_mois, old.pieces, old.description, old.telephone, old.photos) then
      new.status := 'en_attente';
      new.motif_refus := null;
    else
      new.status := old.status;
    end if;
  end if;
  return new;
end $$;

drop trigger if exists proteger_annonce on public.annonces;
create trigger proteger_annonce
  before insert or update on public.annonces
  for each row execute function public.proteger_annonce();

-- ---------- Signalements ----------
create table if not exists public.signalements (
  id uuid primary key default gen_random_uuid(),
  annonce_id uuid not null references public.annonces (id) on delete cascade,
  motif text not null,
  created_at timestamptz not null default now()
);

-- ---------- Sécurité (RLS) ----------
alter table public.profils enable row level security;
alter table public.annonces enable row level security;
alter table public.signalements enable row level security;

drop policy if exists "profil: lire le sien" on public.profils;
create policy "profil: lire le sien" on public.profils
  for select to authenticated using (id = auth.uid());

drop policy if exists "annonces: lecture" on public.annonces;
create policy "annonces: lecture" on public.annonces
  for select to anon, authenticated
  using ((status = 'verifiee' and disponible) or proprietaire = auth.uid());

drop policy if exists "annonces: creer" on public.annonces;
create policy "annonces: creer" on public.annonces
  for insert to authenticated with check (proprietaire = auth.uid());

drop policy if exists "annonces: modifier les siennes" on public.annonces;
create policy "annonces: modifier les siennes" on public.annonces
  for update to authenticated
  using (proprietaire = auth.uid()) with check (proprietaire = auth.uid());

drop policy if exists "annonces: supprimer les siennes" on public.annonces;
create policy "annonces: supprimer les siennes" on public.annonces
  for delete to authenticated using (proprietaire = auth.uid());

drop policy if exists "signalements: envoyer" on public.signalements;
create policy "signalements: envoyer" on public.signalements
  for insert to anon, authenticated with check (char_length(motif) between 3 and 1000);

grant usage on schema public to anon, authenticated;
grant select on public.annonces to anon, authenticated;
grant insert, update, delete on public.annonces to authenticated;
grant select on public.profils to authenticated;
grant insert on public.signalements to anon, authenticated;

-- ---------- Photos (stockage public en lecture) ----------
insert into storage.buckets (id, name, public)
values ('photos', 'photos', true)
on conflict (id) do nothing;

drop policy if exists "photos: envoyer dans son dossier" on storage.objects;
create policy "photos: envoyer dans son dossier" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'photos' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "photos: supprimer les siennes" on storage.objects;
create policy "photos: supprimer les siennes" on storage.objects
  for delete to authenticated
  using (bucket_id = 'photos' and (storage.foldername(name))[1] = auth.uid()::text);

-- ---------- Administration (appli Locavériss Admin) ----------
-- Seuls les comptes présents dans la table admins peuvent valider, refuser
-- ou supprimer n'importe quelle annonce et lire les signalements.
-- Pour nommer un administrateur :
--   insert into public.admins (id) select id from auth.users where email = 'son@email.com';
create table if not exists public.admins (
  id uuid primary key references auth.users (id) on delete cascade,
  created_at timestamptz not null default now()
);
alter table public.admins enable row level security;

create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.admins where id = auth.uid());
$$;

create or replace function public.proteger_annonce()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null or public.is_admin() then
    return new;                       -- tableau de bord ou administrateur
  end if;
  if tg_op = 'INSERT' then
    new.proprietaire := auth.uid();
    new.status := 'en_attente';
    new.motif_refus := null;
  else
    new.proprietaire := old.proprietaire;
    new.motif_refus := old.motif_refus;
    if row(new.titre, new.type, new.ville, new.quartier, new.loyer, new.caution_mois,
           new.avance_mois, new.pieces, new.description, new.telephone, new.photos)
       is distinct from
       row(old.titre, old.type, old.ville, old.quartier, old.loyer, old.caution_mois,
           old.avance_mois, old.pieces, old.description, old.telephone, old.photos) then
      new.status := 'en_attente';
      new.motif_refus := null;
    else
      new.status := old.status;
    end if;
  end if;
  return new;
end $$;

drop policy if exists "annonces: lecture" on public.annonces;
create policy "annonces: lecture" on public.annonces
  for select to anon, authenticated
  using ((status = 'verifiee' and disponible) or proprietaire = auth.uid() or public.is_admin());

drop policy if exists "annonces: admin modifie" on public.annonces;
create policy "annonces: admin modifie" on public.annonces
  for update to authenticated using (public.is_admin()) with check (public.is_admin());

drop policy if exists "annonces: admin supprime" on public.annonces;
create policy "annonces: admin supprime" on public.annonces
  for delete to authenticated using (public.is_admin());

drop policy if exists "profil: lire le sien" on public.profils;
create policy "profil: lire le sien" on public.profils
  for select to authenticated using (id = auth.uid() or public.is_admin());

drop policy if exists "signalements: admin lit" on public.signalements;
create policy "signalements: admin lit" on public.signalements
  for select to authenticated using (public.is_admin());

drop policy if exists "signalements: admin supprime" on public.signalements;
create policy "signalements: admin supprime" on public.signalements
  for delete to authenticated using (public.is_admin());

grant select, delete on public.signalements to authenticated;
grant execute on function public.is_admin() to anon, authenticated;

-- ---------- Profil locataire, demandes et avis ----------
-- Profil locataire : le chercheur le remplit dans l'appli Locavériss ; le propriétaire
-- le voit dans Locavériss Pro quand le chercheur lui envoie une demande.
alter table public.profils
  add column if not exists profession text not null default '',
  add column if not exists situation text not null default '',
  add column if not exists personnes integer not null default 1,
  add column if not exists budget integer not null default 0,
  add column if not exists zone text not null default '',
  add column if not exists presentation text not null default '';

-- Chaque compte doit avoir sa ligne de profil (demandes et avis s'y rattachent).
insert into public.profils (id, nom, telephone, type_compte)
select u.id,
       coalesce(u.raw_user_meta_data->>'nom', ''),
       coalesce(u.raw_user_meta_data->>'telephone', ''),
       coalesce(u.raw_user_meta_data->>'type_compte', 'particulier')
from auth.users u
on conflict (id) do nothing;

-- Note moyenne et nombre d'avis, recalculés automatiquement à chaque avis.
alter table public.annonces
  add column if not exists note_moyenne numeric(2,1) not null default 0,
  add column if not exists nb_avis integer not null default 0;

create table if not exists public.avis (
  id uuid primary key default gen_random_uuid(),
  annonce_id uuid not null references public.annonces (id) on delete cascade,
  auteur uuid not null default auth.uid() references public.profils (id) on delete cascade,
  auteur_nom text not null default '',
  note smallint not null check (note between 1 and 5),
  commentaire text not null default '' check (char_length(commentaire) <= 1000),
  created_at timestamptz not null default now(),
  unique (annonce_id, auteur)                 -- un seul avis par personne et par annonce
);
create index if not exists avis_annonce_idx on public.avis (annonce_id, created_at desc);

create table if not exists public.demandes (
  id uuid primary key default gen_random_uuid(),
  annonce_id uuid not null references public.annonces (id) on delete cascade,
  locataire uuid not null default auth.uid() references public.profils (id) on delete cascade,
  message text not null default '' check (char_length(message) <= 1000),
  status text not null default 'nouvelle'
    check (status in ('nouvelle', 'acceptee', 'refusee')),
  created_at timestamptz not null default now(),
  unique (annonce_id, locataire)              -- une seule demande par personne et par annonce
);
create index if not exists demandes_annonce_idx on public.demandes (annonce_id, created_at desc);
create index if not exists demandes_locataire_idx on public.demandes (locataire);

-- Le nom affiché sur un avis vient du profil : impossible de signer à la place d'un autre.
create or replace function public.preparer_avis()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'UPDATE' then
    new.auteur := old.auteur;
    new.annonce_id := old.annonce_id;
    new.created_at := now();
  end if;
  select nullif(trim(nom), '') into new.auteur_nom from public.profils where id = new.auteur;
  new.auteur_nom := coalesce(new.auteur_nom, 'Utilisateur');
  return new;
end $$;

drop trigger if exists preparer_avis on public.avis;
create trigger preparer_avis
  before insert or update on public.avis
  for each row execute function public.preparer_avis();

create or replace function public.maj_note_annonce()
returns trigger language plpgsql security definer set search_path = public as $$
declare
  cible uuid;
begin
  if tg_op = 'DELETE' then cible := old.annonce_id; else cible := new.annonce_id; end if;
  update public.annonces a
     set note_moyenne = coalesce((select round(avg(v.note), 1) from public.avis v where v.annonce_id = cible), 0),
         nb_avis = (select count(*) from public.avis v where v.annonce_id = cible)
   where a.id = cible;
  return null;
end $$;

drop trigger if exists maj_note_annonce on public.avis;
create trigger maj_note_annonce
  after insert or update or delete on public.avis
  for each row execute function public.maj_note_annonce();

-- La note d'une annonce ne change que par les avis (pg_trigger_depth() > 1) :
-- ni le propriétaire ni personne d'autre ne peut l'écrire directement.
create or replace function public.proteger_annonce()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    new.note_moyenne := 0;
    new.nb_avis := 0;
  elsif pg_trigger_depth() = 1 then
    new.note_moyenne := old.note_moyenne;
    new.nb_avis := old.nb_avis;
  end if;
  if auth.uid() is null or public.is_admin() then
    return new;                       -- tableau de bord ou administrateur
  end if;
  if tg_op = 'INSERT' then
    new.proprietaire := auth.uid();
    new.status := 'en_attente';
    new.motif_refus := null;
  else
    new.proprietaire := old.proprietaire;
    new.motif_refus := old.motif_refus;
    if row(new.titre, new.type, new.ville, new.quartier, new.loyer, new.caution_mois,
           new.avance_mois, new.pieces, new.description, new.telephone, new.photos)
       is distinct from
       row(old.titre, old.type, old.ville, old.quartier, old.loyer, old.caution_mois,
           old.avance_mois, old.pieces, old.description, old.telephone, old.photos) then
      new.status := 'en_attente';
      new.motif_refus := null;
    else
      new.status := old.status;
    end if;
  end if;
  return new;
end $$;

alter table public.avis enable row level security;
alter table public.demandes enable row level security;

-- Avis : tout le monde les lit ; il faut un compte pour en donner un,
-- sur une annonce vérifiée qui n'est pas la sienne.
drop policy if exists "avis: lecture" on public.avis;
create policy "avis: lecture" on public.avis
  for select to anon, authenticated using (true);

drop policy if exists "avis: donner le sien" on public.avis;
create policy "avis: donner le sien" on public.avis
  for insert to authenticated
  with check (
    auteur = auth.uid()
    and exists (select 1 from public.annonces a
                where a.id = annonce_id and a.status = 'verifiee' and a.proprietaire <> auth.uid())
  );

drop policy if exists "avis: modifier le sien" on public.avis;
create policy "avis: modifier le sien" on public.avis
  for update to authenticated using (auteur = auth.uid()) with check (auteur = auth.uid());

drop policy if exists "avis: supprimer" on public.avis;
create policy "avis: supprimer" on public.avis
  for delete to authenticated using (auteur = auth.uid() or public.is_admin());

-- Demandes : le chercheur envoie et voit les siennes ; le propriétaire de l'annonce
-- les reçoit et y répond (acceptée / refusée).
drop policy if exists "demandes: envoyer" on public.demandes;
create policy "demandes: envoyer" on public.demandes
  for insert to authenticated
  with check (
    locataire = auth.uid()
    and exists (select 1 from public.annonces a
                where a.id = annonce_id and a.status = 'verifiee' and a.proprietaire <> auth.uid())
  );

drop policy if exists "demandes: lecture" on public.demandes;
create policy "demandes: lecture" on public.demandes
  for select to authenticated
  using (
    locataire = auth.uid()
    or public.is_admin()
    or exists (select 1 from public.annonces a
               where a.id = annonce_id and a.proprietaire = auth.uid())
  );

drop policy if exists "demandes: le propriétaire répond" on public.demandes;
create policy "demandes: le propriétaire répond" on public.demandes
  for update to authenticated
  using (exists (select 1 from public.annonces a
                 where a.id = annonce_id and a.proprietaire = auth.uid()))
  with check (exists (select 1 from public.annonces a
                      where a.id = annonce_id and a.proprietaire = auth.uid()));

drop policy if exists "demandes: retirer la sienne" on public.demandes;
create policy "demandes: retirer la sienne" on public.demandes
  for delete to authenticated using (locataire = auth.uid());

-- Profil : chacun modifie le sien ; un propriétaire lit celui des chercheurs
-- qui lui ont envoyé une demande (et seulement ceux-là).
drop policy if exists "profil: modifier le sien" on public.profils;
create policy "profil: modifier le sien" on public.profils
  for update to authenticated using (id = auth.uid()) with check (id = auth.uid());

drop policy if exists "profil: lu par le propriétaire contacté" on public.profils;
create policy "profil: lu par le propriétaire contacté" on public.profils
  for select to authenticated
  using (exists (select 1 from public.demandes d
                 join public.annonces a on a.id = d.annonce_id
                 where d.locataire = profils.id and a.proprietaire = auth.uid()));

-- Droits colonne par colonne : on ne peut ni se déclarer « vérifié », ni choisir
-- le statut de sa demande, ni signer un avis à la place d'un autre.
revoke all on public.avis from anon, authenticated;
revoke all on public.demandes from anon, authenticated;
revoke insert, update, delete on public.profils from anon, authenticated;
grant select on public.avis to anon, authenticated;
grant insert (annonce_id, note, commentaire) on public.avis to authenticated;
grant update (note, commentaire) on public.avis to authenticated;
grant delete on public.avis to authenticated;
grant select on public.demandes to authenticated;
grant insert (annonce_id, message) on public.demandes to authenticated;
grant update (status) on public.demandes to authenticated;
grant delete on public.demandes to authenticated;
grant update (nom, telephone, profession, situation, personnes, budget, zone, presentation)
  on public.profils to authenticated;

-- =====================================================================
-- Mise à jour 2 : filtres étudiants, règle légale des 2 mois,
-- justificatifs du propriétaire, Pass Réservation (Mobile Money).
-- =====================================================================

-- ---------- Filtres étudiants ----------
alter table public.annonces
  add column if not exists campus text not null default '',
  add column if not exists distance_campus integer not null default 0,      -- minutes à pied
  add column if not exists compteur_individuel boolean not null default false, -- compteur CIE/SODECI
  add column if not exists frais_agence integer not null default 0;
create index if not exists annonces_campus_idx on public.annonces (campus);

-- ---------- Justificatifs (pièce d'identité + preuve de propriété) ----------
-- Stockés dans un espace PRIVÉ : seuls le propriétaire et les administrateurs les voient.
create table if not exists public.justificatifs (
  id uuid primary key default gen_random_uuid(),
  annonce_id uuid not null references public.annonces (id) on delete cascade,
  proprietaire uuid not null default auth.uid() references auth.users (id) on delete cascade,
  type text not null check (type in ('cni', 'propriete')),
  chemin text not null,
  created_at timestamptz not null default now()
);
create index if not exists justificatifs_annonce_idx on public.justificatifs (annonce_id);
alter table public.justificatifs enable row level security;

drop policy if exists "justificatifs: envoyer" on public.justificatifs;
create policy "justificatifs: envoyer" on public.justificatifs
  for insert to authenticated
  with check (
    proprietaire = auth.uid()
    and chemin like auth.uid()::text || '/%'
    and exists (select 1 from public.annonces a where a.id = annonce_id and a.proprietaire = auth.uid())
  );

drop policy if exists "justificatifs: lecture" on public.justificatifs;
create policy "justificatifs: lecture" on public.justificatifs
  for select to authenticated using (proprietaire = auth.uid() or public.is_admin());

drop policy if exists "justificatifs: supprimer" on public.justificatifs;
create policy "justificatifs: supprimer" on public.justificatifs
  for delete to authenticated using (proprietaire = auth.uid() or public.is_admin());

revoke all on public.justificatifs from anon, authenticated;
grant select, delete on public.justificatifs to authenticated;
grant insert (annonce_id, type, chemin) on public.justificatifs to authenticated;

insert into storage.buckets (id, name, public)
values ('justificatifs', 'justificatifs', false)
on conflict (id) do update set public = false;

drop policy if exists "justificatifs: envoyer dans son dossier" on storage.objects;
create policy "justificatifs: envoyer dans son dossier" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'justificatifs' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "justificatifs: lire" on storage.objects;
create policy "justificatifs: lire" on storage.objects
  for select to authenticated
  using (bucket_id = 'justificatifs'
         and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()));

drop policy if exists "justificatifs: supprimer" on storage.objects;
create policy "justificatifs: supprimer" on storage.objects
  for delete to authenticated
  using (bucket_id = 'justificatifs'
         and ((storage.foldername(name))[1] = auth.uid()::text or public.is_admin()));

-- ---------- Protection des annonces (version finale) ----------
-- + aucune validation sans pièce d'identité ET preuve de propriété ;
-- + loi n° 2019-576 (Code de la construction et de l'habitat) : 2 mois de caution
--   et 2 mois d'avance au maximum.
create or replace function public.proteger_annonce()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'INSERT' then
    new.note_moyenne := 0;
    new.nb_avis := 0;
  elsif pg_trigger_depth() = 1 then
    new.note_moyenne := old.note_moyenne;
    new.nb_avis := old.nb_avis;
  end if;

  if tg_op = 'UPDATE' and new.status = 'verifiee' and old.status is distinct from 'verifiee' then
    if not exists (select 1 from public.justificatifs j where j.annonce_id = new.id and j.type = 'cni')
       or not exists (select 1 from public.justificatifs j where j.annonce_id = new.id and j.type = 'propriete') then
      raise exception 'Validation impossible : il manque la pièce d''identité ou la preuve de propriété du bailleur.';
    end if;
  end if;

  if auth.uid() is null or public.is_admin() then
    return new;                       -- tableau de bord ou administrateur
  end if;

  if tg_op = 'INSERT' then
    new.proprietaire := auth.uid();
    new.status := 'en_attente';
    new.motif_refus := null;
  else
    new.proprietaire := old.proprietaire;
    new.motif_refus := old.motif_refus;
    if row(new.titre, new.type, new.ville, new.quartier, new.loyer, new.caution_mois,
           new.avance_mois, new.pieces, new.description, new.telephone, new.photos,
           new.campus, new.distance_campus, new.compteur_individuel, new.frais_agence)
       is distinct from
       row(old.titre, old.type, old.ville, old.quartier, old.loyer, old.caution_mois,
           old.avance_mois, old.pieces, old.description, old.telephone, old.photos,
           old.campus, old.distance_campus, old.compteur_individuel, old.frais_agence) then
      new.status := 'en_attente';
      new.motif_refus := null;
    else
      new.status := old.status;
      return new;                     -- simple changement de disponibilité
    end if;
  end if;

  if new.caution_mois > 2 or new.avance_mois > 2 then
    raise exception 'La loi limite la caution à 2 mois et l''avance à 2 mois (Code de la construction et de l''habitat).';
  end if;
  if new.caution_mois < 0 or new.avance_mois < 0 or new.frais_agence < 0 or new.distance_campus < 0 then
    raise exception 'Valeurs négatives non autorisées.';
  end if;
  return new;
end $$;

-- ---------- Pass Réservation ----------
-- Réglages : tant que pass_obligatoire = false, les contacts restent libres.
-- Pour lancer le Pass :  update public.reglages set pass_obligatoire = true;
-- Offre de lancement :   update public.reglages set prix_pass = 1000;
create table if not exists public.reglages (
  id boolean primary key default true check (id),
  pass_obligatoire boolean not null default false,
  prix_pass integer not null default 1500 check (prix_pass > 0 and prix_pass % 5 = 0),
  duree_pass_jours integer not null default 30 check (duree_pass_jours > 0)
);
insert into public.reglages (id) values (true) on conflict (id) do nothing;
alter table public.reglages enable row level security;
drop policy if exists "reglages: lecture" on public.reglages;
create policy "reglages: lecture" on public.reglages for select to anon, authenticated using (true);
revoke all on public.reglages from anon, authenticated;
grant select on public.reglages to anon, authenticated;

-- Paiements du Pass. Seule la fonction pass-cinetpay (clé service) les écrit.
create table if not exists public.pass (
  id uuid primary key default gen_random_uuid(),
  utilisateur uuid not null references auth.users (id) on delete cascade,
  transaction_id text not null unique,
  montant integer not null,
  duree_jours integer not null default 30,
  statut text not null default 'en_attente' check (statut in ('en_attente', 'paye', 'echec')),
  debut timestamptz,
  fin timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists pass_utilisateur_idx on public.pass (utilisateur, statut, fin desc);
alter table public.pass enable row level security;
drop policy if exists "pass: lire le sien" on public.pass;
create policy "pass: lire le sien" on public.pass
  for select to authenticated using (utilisateur = auth.uid() or public.is_admin());
revoke all on public.pass from anon, authenticated;
grant select on public.pass to authenticated;

create or replace function public.pass_actif(u uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.pass p
                 where p.utilisateur = u and p.statut = 'paye' and p.fin > now());
$$;

create or replace function public.pass_requis()
returns boolean language sql stable security definer set search_path = public as $$
  select coalesce((select pass_obligatoire from public.reglages limit 1), false);
$$;

-- État du Pass pour l'appli chercheur : obligatoire ?, prix, durée, fin du Pass en cours.
create or replace function public.etat_pass()
returns json language sql stable security definer set search_path = public as $$
  select json_build_object(
    'obligatoire', r.pass_obligatoire,
    'prix', r.prix_pass,
    'duree', r.duree_pass_jours,
    'fin', (select max(p.fin) from public.pass p
            where p.utilisateur = auth.uid() and p.statut = 'paye' and p.fin > now())
  )
  from public.reglages r limit 1;
$$;

-- Le numéro du propriétaire n'est plus lisible directement : il passe par cette fonction,
-- qui le donne si le Pass n'est pas obligatoire, si le Pass est actif,
-- au propriétaire lui-même et aux administrateurs. Sinon : null.
create or replace function public.contact_annonce(p_annonce uuid)
returns text language plpgsql stable security definer set search_path = public as $$
declare
  a record;
begin
  select id, proprietaire, telephone, status, disponible into a
    from public.annonces where id = p_annonce;
  if not found then return null; end if;
  if auth.uid() is not null and (a.proprietaire = auth.uid() or public.is_admin()) then
    return a.telephone;
  end if;
  if not (a.status = 'verifiee' and a.disponible) then return null; end if;
  if public.pass_requis() and not public.pass_actif(auth.uid()) then return null; end if;
  return a.telephone;
end $$;

revoke execute on function public.pass_actif(uuid) from public, anon;
grant execute on function public.pass_actif(uuid) to authenticated;
grant execute on function public.pass_requis() to anon, authenticated;
grant execute on function public.etat_pass() to anon, authenticated;
grant execute on function public.contact_annonce(uuid) to anon, authenticated;

-- Demande de visite : Pass actif exigé quand le Pass est obligatoire.
drop policy if exists "demandes: envoyer" on public.demandes;
create policy "demandes: envoyer" on public.demandes
  for insert to authenticated
  with check (
    locataire = auth.uid()
    and exists (select 1 from public.annonces a
                where a.id = annonce_id and a.status = 'verifiee' and a.proprietaire <> auth.uid())
    and (not public.pass_requis() or public.pass_actif(auth.uid()))
  );

-- Lecture des annonces colonne par colonne : tout sauf le téléphone.
revoke select on public.annonces from anon, authenticated;
grant select (id, proprietaire, titre, type, ville, quartier, loyer, caution_mois, avance_mois,
              pieces, description, photos, disponible, status, motif_refus, created_at,
              note_moyenne, nb_avis, campus, distance_campus, compteur_individuel, frais_agence)
  on public.annonces to anon, authenticated;
