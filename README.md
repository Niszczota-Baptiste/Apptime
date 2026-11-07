# Mon Temps

Application Android personnelle de suivi du temps : quelle activité est en cours et depuis
combien de temps, et comment la journée et la semaine ont été réparties. Entièrement hors
connexion, sans compte, sans publicité ni statistiques d'usage.

Cible : Redmi Note 17 Pro 5G (6 Go de RAM, Android 16 / HyperOS 3). Compatible Android 8.0
(API 26) et plus.

## Fonctions (version 0.1.0)

- **Aujourd'hui** : activité en cours (pastille, nom, chronomètre hh:mm:ss, heure de début),
  « Arrêter le suivi », raccourcis en un appui, total suivi, répartition et objectifs du jour.
- **Sous-catégories** : un appui sur une activité qui en a ouvre le choix — Travail : Perso / Pro ;
  Pause : YouTube / Jeu / Autre ; Sport : Muscu / Foot / Course / Autre ; Écriture : Écrire / Lire ;
  Musique : Composition / Jouer ; Déplacement : Voiture / À pied / Transports / Autre (ou « Sans
  précision »). Affiché « Travail · Pro » ; bilans détaillés par sous-catégorie ; ajout, renommage
  et retrait dans Activités.
- **Annuler** : après chaque changement, « Annuler » pendant quelques secondes (appui accidentel).
- **Démarrer plus tôt** : appui long sur une activité → il y a 5, 10, 15, 30, 60 min ou une heure
  précise, sans jamais chevaucher la session précédente.
- **Reprendre** : pendant une interruption (Pause, Repas…), « Reprendre Travail » en un appui
  (écran principal, notification, widget, tuile).
- **Notification de suivi** : chronomètre natif, actions rapides, ouverture de l'app, option pour
  masquer le nom sur l'écran verrouillé ; sur Android 16, affichage en haut de l'écran
  (« Live Update », chronomètre dans la barre d'état) si le système l'autorise.
- **Historique** par jour : frise des 24 h, sessions et périodes « Non suivi » à attribuer en un
  appui ; modifier, ajouter, supprimer (avec confirmation). Aucun chevauchement possible.
- **Bilans** jour et semaine : durées, pourcentages *du temps suivi*, temps non suivi explicite,
  objectifs, comparaison avec la semaine précédente (au même moment), moyenne par jour, séries.
- **Activités** : 11 activités par défaut (dont Déplacement et Sommeil), création, renommage,
  couleur, ordre, archivage, interruption (reprise proposée), objectif par jour ou par semaine,
  sous-catégories.
- **Écran d'accueil** : widgets « Suivi en cours » et « Activités » ; **centre de contrôle** :
  tuile « Mon Temps ».
- **Rappels** (facultatifs, sobres) : session longue, « rien n'est suivi » dans une plage horaire,
  résumé du soir (silencieux).
- **Coucher et réveil automatiques** (facultatifs, désactivés par défaut) : à l'heure et les jours
  choisis, Sommeil démarre puis s'arrête tout seul, avec une notification silencieuse
  (« Annuler », « Je dors encore »). Ce que vous faites entre-temps l'emporte toujours.
- **Réglages par catégories** repliables (Notification, Activités, Sommeil, Rappels, Écran
  d'accueil, Montre Garmin, Données), avec un résumé de l'état sur chaque en-tête.
- **Données** : export CSV, sauvegarde JSON versionnée, restauration validée et atomique,
  **sauvegarde automatique hebdomadaire** dans un dossier choisi (8 copies conservées).
- **Session oubliée** : à la réouverture, après un seuil réglable ou un redémarrage du téléphone,
  l'app propose de continuer ou de corriger l'heure de fin. Rien n'est inventé.
- **Montre Garmin** (Forerunner 965, [`docs/GARMIN.md`](docs/GARMIN.md)) : app Connect IQ
  « Mon Temps » (aperçu, chronomètre, changer / reprendre / arrêter au poignet, sous-catégories,
  file hors ligne appliquée à l'heure d'origine), rappels au poignet via Garmin Connect, import des
  séances de la montre (Health Connect, lecture seule, après votre choix).

## Interface

Pensée pour ne pas distraire :

- palette neutre « encre / papier », clair ou sombre selon le système, sans couleurs dynamiques ;
- les couleurs des activités (teintes douces) sont les seuls accents, sous forme de pastilles ;
- un grand chronomètre en graisse fine, à chiffres fixes, au centre de l'écran ;
- l'activité en cours est affichée en négatif (fond encre) avec la mention « En cours » ;
- les onze raccourcis tiennent sur un écran, et ne bougent pas quand on démarre ou arrête ;
- pas de cartes ni d'ombres, une barre de navigation fine, des titres intégrés au contenu ;
- aucune icône ni émoji : une activité = un nom et une pastille de couleur ;
- les widgets reprennent exactement ce langage (clair/sombre selon le système).

Ajout des widgets et de la tuile : Réglages → Écran d'accueil (si l'écran d'accueil ou
l'interface système ne propose pas l'ajout direct : appui long sur l'écran d'accueil → Widgets →
Mon Temps ; centre de contrôle → Modifier → Mon Temps).

Captures (rendues par les tests, données réelles en base) : [`docs/screenshots/`](docs/screenshots).

## Installation de l'APK de développement

1. Copier `app-debug.apk` sur le téléphone (ou `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
2. Autoriser l'installation depuis la source utilisée, installer, ouvrir « Mon Temps ».
3. Au premier appui sur une activité, Android 13+ demande l'autorisation de notification.

La variante debug a l'identifiant `fr.montemps.debug` : elle peut coexister avec une future version
release (`fr.montemps`). Une APK debug compilée sur une autre machine est signée avec une autre clé :
il faut alors désinstaller l'ancienne (exporter d'abord une sauvegarde JSON).

### Changer de téléphone

Les données ne suivent pas le transfert Android ou Xiaomi (sauvegarde Android désactivée
volontairement). Sur l'ancien téléphone : Réglages → « Créer une sauvegarde » ; copier le fichier
JSON sur le nouveau ; y installer l'APK puis Réglages → « Restaurer une sauvegarde ». Activités,
historique, objectifs et réglages reviennent. À refaire sur le nouveau téléphone : autorisation de
notification, « Démarrage automatique » (HyperOS), dossier de la sauvegarde automatique, widgets
et tuile.

## Compilation

Prérequis : JDK 17 ou plus récent, Android SDK (plateforme 36). Le wrapper Gradle télécharge Gradle 8.14.3.

```bash
echo "sdk.dir=/chemin/vers/android-sdk" > local.properties   # ou variable ANDROID_HOME
./gradlew :app:assembleDebug          # APK : app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # tests JVM + Robolectric (148 tests, régénère les captures)
./gradlew :app:lintDebug              # analyse Android Lint
./gradlew :app:assembleRelease        # APK release minifiée (non signée : configurer un keystore)
```

Les tests Robolectric téléchargent une image Android au premier lancement. Derrière un réseau
limité, un miroir Maven peut être indiqué : `ROBOLECTRIC_REPO_URL=https://… ./gradlew …`.
Le test `ScreenshotTest` régénère les captures dans `docs/screenshots/`.

### Versions retenues

Kotlin 2.2.21, AGP 8.13.2, Gradle 8.14.3, KSP 2.2.21-2.0.4, Compose BOM 2025.12.00
(Material 3 1.4), Room 2.8.4, DataStore 1.1.7, Navigation 2.9.6, kotlinx-serialization 1.9.0,
Robolectric 4.16. compileSdk/targetSdk 36, minSdk 26. Combinaison stable et cohérente choisie
plutôt que les toutes dernières versions majeures (AGP 9 / Kotlin 2.4) ; voir `gradle/libs.versions.toml`.

## Renommer l'application

Le nom affiché vient uniquement de `app_name` dans `app/src/main/res/values/strings.xml`.
Ne pas changer `applicationId` après installation (Android le traiterait comme une autre app).

## Structure

```
app/src/main/java/fr/montemps/
├── domain/                 Logique métier pure (aucune dépendance Android)
│   ├── model/              TrackedActivity, Session, CommandSource, activités par défaut
│   ├── TimeTracker.kt      Démarrer / changer / arrêter (interface TrackingCommands)
│   ├── SessionEditor.kt    Corrections de l'historique
│   ├── SessionValidator.kt Règles de validation (chevauchements, bornes)
│   ├── ActivityCatalog.kt  Gestion des activités
│   ├── TrackingStore.kt    Port de persistance
│   ├── watch/ workouts/    Commandes de la montre ; propositions d'import des séances
│   ├── sleep/              Échéances du coucher et du réveil automatiques
│   └── report/             Calcul des bilans, formatage français
├── data/                   Room (base, DAO, triggers d'intégrité), DataStore, sauvegarde CSV/JSON, Health Connect
├── notification/           Notifications (suivi, rappels, coucher/réveil), actions, alarmes, événements système
├── surfaces/               Widgets d'écran d'accueil et tuile du centre de contrôle
├── garmin/                 Liaison avec la montre (SDK Connect IQ, protocole)
├── ui/                     Compose : today, history, reports, activities, settings, workouts, components
├── AppContainer.kt         Assemblage des dépendances (sans framework d'injection)
└── MonTempsApp.kt / MainActivity.kt
garmin/                     App montre Connect IQ (Monkey C) : manifest.xml, source/, resources/
docs/ARCHITECTURE.md        Décisions, comportement temporel, inter-apps
docs/GARMIN.md              Montre Garmin : installation, utilisation, protocole, limites
docs/TEST_TELEPHONE.md      Procédure de test manuelle sur le téléphone
```

## Vérifications et limites

Voir [`docs/VERIFICATION.md`](docs/VERIFICATION.md) : ce qui a été réellement vérifié, comment, et
ce qui reste à valider sur l'appareil physique (aucun test n'a été fait sur un vrai téléphone).
