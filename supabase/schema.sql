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
