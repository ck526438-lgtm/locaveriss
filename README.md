# Locavériss — Location et immobilier

Annonces vérifiées de logements, sans faux démarcheurs. Trois applis Android issues du même code :

| Appli | Pour qui | Fonctions |
|---|---|---|
| **Locavériss** | Personnes en quête de logement | Recherche par ville/quartier, tri (récents, mieux notés, moins chers), filtres type et loyer max ; fiche avec photos, caution, avance ; **avis et notes** sur chaque annonce ; favoris ; **profil locataire** et demande au propriétaire ; contact WhatsApp / appel ; signalement |
| **Locavériss Pro** | Propriétaires et agences | Compte, publication avec photos, suivi du statut, modification, « loué », suppression ; **demandes reçues avec le profil de chaque locataire**, accepter / refuser |
| **Locavériss Admin** | Équipe Locavériss | Annonces à vérifier, boutons Valider / Refuser (avec motif), signalements, suppression |

Les applis démarrent **vides** : aucune annonce de démonstration.

## Comment une annonce est vérifiée

1. Le propriétaire publie → statut `en_attente` (invisible pour les chercheurs).
2. L'administrateur ouvre **Locavériss Admin**, contrôle l'annonce (photos, appel du
   propriétaire) et touche **Valider** ou **Refuser** (avec un motif).
3. Toute modification du contenu par le propriétaire renvoie l'annonce en `en_attente`.

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

## Design

Interface noir / blanc / gris doux, grandes cartes photo arrondies, boutons en pilule et barre
du bas flottante. Police : Outfit (licence OFL, voir `app/src/main/OUTFIT-FONT-LICENSE.txt`).

## Mise en place de Supabase

1. Créer un projet sur supabase.com.
2. SQL Editor → coller `supabase/schema.sql` → Run (le fichier peut être relancé sans risque
   après une mise à jour : il ne supprime aucune donnée).
3. Authentication > Sign In / Providers > Email : désactiver « Confirm email »
   (sinon chaque propriétaire doit cliquer sur un lien reçu par mail avant de se connecter).
4. Project Settings > API : copier l'URL du projet et la clé publique (anon / publishable)
   dans `gradle.properties` (`SUPABASE_URL`, `SUPABASE_KEY`).
   Ne jamais y mettre la clé `service_role` / `secret`.

## Compilation

Chaque envoi sur `main` compile les trois APK (GitHub Actions) et les publie dans **Releases**.
En local : `gradle assembleChercheurDebug assembleProDebug assembleAdminDebug` (JDK 17, Gradle 8.7).
