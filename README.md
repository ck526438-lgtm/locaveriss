# Locavériss — Location et immobilier

Annonces vérifiées de logements, sans faux démarcheurs. Deux applis Android issues du même code :

| Appli | Pour qui | Fonctions |
|---|---|---|
| **Locavériss** | Personnes en quête de logement | Recherche par ville/quartier, type et loyer max ; fiche avec photos, caution, avance ; contact WhatsApp / appel ; signalement |
| **Locavériss Pro** | Propriétaires et agences | Compte, publication avec photos, suivi du statut, modification, « loué », suppression |

Les applis démarrent **vides** : aucune annonce de démonstration.

## Comment une annonce est vérifiée

1. Le propriétaire publie → statut `en_attente` (invisible pour les chercheurs).
2. L'administrateur ouvre Supabase > Table Editor > `annonces`, contrôle l'annonce
   (appel du propriétaire, photos, etc.) et met `status` à `verifiee`
   (ou `refusee` avec un `motif_refus`).
3. Toute modification du contenu par le propriétaire renvoie l'annonce en `en_attente`.

Les signalements des chercheurs arrivent dans la table `signalements`.

## Mise en place de Supabase

1. Créer un projet sur supabase.com.
2. SQL Editor → coller `supabase/schema.sql` → Run.
3. Authentication > Sign In / Providers > Email : désactiver « Confirm email »
   (sinon chaque propriétaire doit cliquer sur un lien reçu par mail avant de se connecter).
4. Project Settings > API : copier l'URL du projet et la clé publique (anon / publishable)
   dans `gradle.properties` (`SUPABASE_URL`, `SUPABASE_KEY`).
   Ne jamais y mettre la clé `service_role` / `secret`.

## Compilation

Chaque envoi sur `main` compile les deux APK (GitHub Actions) et les publie dans **Releases**.
En local : `gradle assembleChercheurDebug assembleProDebug` (JDK 17, Gradle 8.7).
