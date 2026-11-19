# Locavériss — Location et immobilier

Annonces vérifiées de logements, sans faux démarcheurs. Trois applis Android issues du même code :

| Appli | Pour qui | Fonctions |
|---|---|---|
| **Locavériss** | Personnes en quête de logement | Recherche par ville/quartier, tri (récents, mieux notés, moins chers), filtres type, loyer max, **campus/école, distance du campus, compteur CIE/SODECI individuel** ; **Pass Réservation** payé par Mobile Money ; fiche avec photos, caution, avance ; **avis et notes** sur chaque annonce ; favoris ; **profil locataire** et demande au propriétaire ; contact WhatsApp / appel ; signalement |
| **Locavériss Pro** | Propriétaires et agences | Compte, publication avec photos, **justificatifs obligatoires (CNI + preuve de propriété)**, infos étudiants (campus, distance, compteur, frais d'agence), contrôle de la limite légale de 2 mois, suivi du statut, modification, « loué », suppression ; **demandes reçues avec le profil de chaque locataire**, accepter / refuser |
| **Locavériss Admin** | Équipe Locavériss | Annonces à vérifier, **consultation des justificatifs** (liens temporaires), boutons Valider / Refuser (avec motif), signalements, suppression |

Les applis démarrent **vides** : aucune annonce de démonstration.

## Comment une annonce est vérifiée

1. Le propriétaire publie avec sa **pièce d'identité** et une **preuve de propriété** (facture
   CIE/SODECI à son nom, titre, attestation villageoise ou mandat signé) → statut `en_attente`
   (invisible pour les chercheurs). Les justificatifs sont dans un espace privé : seuls le
   propriétaire et les administrateurs peuvent les ouvrir.
2. L'administrateur ouvre **Locavériss Admin**, contrôle l'annonce (photos, appel du
   propriétaire) et touche **Valider** ou **Refuser** (avec un motif).
3. Toute modification du contenu par le propriétaire renvoie l'annonce en `en_attente`.
4. La base refuse de valider une annonce sans les deux justificatifs, et refuse toute annonce
   qui demande plus de **2 mois de caution ou 2 mois d'avance** (loi n° 2019-576, Code de la
   construction et de l'habitat).

Les signalements des chercheurs apparaissent dans Locavériss Admin (liste « Signalements »).
Seuls les comptes inscrits dans la table `admins` peuvent se connecter à l'appli Admin.

## Avis, notes et profil locataire

- **Avis** : tout le monde peut les lire. Pour en donner un, le chercheur crée un compte ;
  un seul avis par personne et par annonce (il peut le modifier). La note moyenne de
  l'annonce est recalculée automatiquement et ne peut pas être modifiée par le propriétaire.
- **Profil locataire** : le chercheur le remplit dans l'onglet Profil (profession, situation,
  nombre de personnes, budget, zone recherchée, présentation). Il n'est visible que par les
  propriétaires à qui il a envoyé une demande, dans Locavériss Pro > Demandes.
- **Favoris** : enregistrés sur le téléphone, sans compte.

## Pass Réservation (Mobile Money)

- Le numéro du propriétaire n'est plus lisible dans la liste des annonces : l'appli le demande
  à la fonction `contact_annonce`, qui le donne si le Pass n'est pas obligatoire ou si le
  chercheur a un Pass actif (le propriétaire et les admins le voient toujours).
- Avec le Pass obligatoire, appeler, écrire sur WhatsApp ou envoyer une demande de visite
  propose le Pass : paiement sur la page CinetPay (Wave, Orange Money, MTN MoMo, Moov Money),
  puis activation automatique au retour dans l'appli. Un Pass encore actif est prolongé.
- Par défaut le Pass est **désactivé** : les contacts restent libres tant que CinetPay n'est pas
  configuré. Réglages dans la table `reglages` :

```sql
update public.reglages set pass_obligatoire = true;   -- activer le Pass
update public.reglages set prix_pass = 1000;          -- offre de lancement (multiple de 5)
update public.reglages set prix_pass = 1500;          -- prix normal
```

Pour offrir un Pass à la main (ex. paiement reçu directement) :

```sql
insert into public.pass (utilisateur, transaction_id, montant, statut, debut, fin)
select id, 'MANUEL-' || gen_random_uuid(), 0, 'paye', now(), now() + interval '30 days'
from auth.users where email = 'etudiant@email.com';
```

## Design

Interface noir / blanc / gris doux, grandes cartes photo arrondies, boutons en pilule et barre
du bas flottante. Police : Outfit (licence OFL, voir `app/src/main/OUTFIT-FONT-LICENSE.txt`).

## Mise en place de Supabase

1. Créer un projet sur supabase.com.
2. SQL Editor → coller `supabase/schema.sql` → Run. **Après chaque mise à jour des applis,
   relancer le fichier** : les nouvelles versions lisent des colonnes ajoutées par le schéma (le fichier peut être relancé sans risque
   après une mise à jour : il ne supprime aucune donnée).
3. Authentication > Sign In / Providers > Email : désactiver « Confirm email »
   (sinon chaque propriétaire doit cliquer sur un lien reçu par mail avant de se connecter).
4. Project Settings > API : copier l'URL du projet et la clé publique (anon / publishable)
   dans `gradle.properties` (`SUPABASE_URL`, `SUPABASE_KEY`).
   Ne jamais y mettre la clé `service_role` / `secret`.

## Paiement CinetPay (fonction `pass-cinetpay`)

1. Créer un compte marchand sur cinetpay.com et récupérer l'**APIKEY** et le **SITE_ID**.
2. Installer la CLI Supabase, puis dans ce dossier :

```bash
supabase link --project-ref <ref-du-projet>
supabase secrets set CINETPAY_APIKEY=xxxx CINETPAY_SITE_ID=xxxx
supabase functions deploy pass-cinetpay --no-verify-jwt
```

`--no-verify-jwt` est nécessaire car CinetPay appelle la fonction sans compte ; la fonction
vérifie elle-même le compte pour `initier` et `verifier`, et relit toujours le statut du paiement
chez CinetPay (une notification ne suffit jamais à activer un Pass).

## Compilation

Chaque envoi sur `main` compile les trois APK (GitHub Actions) et les publie dans **Releases**.
En local : `gradle assembleChercheurDebug assembleProDebug assembleAdminDebug` (JDK 17, Gradle 8.7).
