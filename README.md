# Mon Temps

Application Android personnelle de suivi du temps : quelle activité est en cours et depuis
combien de temps, et comment la journée et la semaine ont été réparties. Entièrement hors
connexion, sans compte, sans publicité ni statistiques d'usage.

Cible : POCO X8 Pro Max (Android 16 / HyperOS 3). Compatible Android 8.0 (API 26) et plus.

## Fonctions (version 0.1.0)

- **Aujourd'hui** : activité en cours (nom, icône, « EN COURS », chronomètre hh:mm:ss, heure de
  début), bouton « Arrêter le suivi », raccourcis en un appui, total suivi et répartition du jour.
- **Notification de suivi** : chronomètre natif, actions rapides vers 2 activités favorites et
  « Arrêter », ouverture de l'app au toucher, option pour masquer le nom sur l'écran verrouillé.
- **Historique** par jour : modifier, ajouter une session oubliée, supprimer (avec confirmation).
  Validation : fin > début, pas de fin dans le futur, aucun chevauchement (session en cours comprise).
- **Bilans** jour et semaine : durées, total suivi, pourcentages *du temps suivi*, temps non suivi
  affiché explicitement, session en cours incluse, sessions à cheval sur minuit réparties.
- **Activités** : 10 activités par défaut, création, renommage, couleur, icône, ordre, archivage.
- **Données** : export CSV, sauvegarde JSON versionnée, restauration validée et atomique.
- **Session oubliée** : à la réouverture, après un seuil réglable ou un redémarrage du téléphone,
  l'app propose de continuer ou de corriger l'heure de fin. Rien n'est inventé.

## Interface

Pensée pour ne pas distraire :

- palette neutre « encre / papier », clair ou sombre selon le système, sans couleurs dynamiques ;
- les couleurs des activités (teintes douces) sont les seuls accents, sous forme de pastilles ;
- un grand chronomètre en graisse fine, à chiffres fixes, au centre de l'écran ;
- l'activité en cours est affichée en négatif (fond encre) avec la mention « En cours » ;
- les dix raccourcis tiennent sur un écran, et ne bougent pas quand on démarre ou arrête ;
- pas de cartes ni d'ombres, une barre de navigation fine, des titres intégrés au contenu ;
- option « Icônes des activités » (Réglages → Affichage) pour remplacer les pastilles par des émojis.

Captures (rendues par les tests, données réelles en base) : [`docs/screenshots/`](docs/screenshots).

## Installation de l'APK de développement

1. Copier `app-debug.apk` sur le téléphone (ou `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
2. Autoriser l'installation depuis la source utilisée, installer, ouvrir « Mon Temps ».
3. Au premier appui sur une activité, Android 13+ demande l'autorisation de notification.

La variante debug a l'identifiant `fr.montemps.debug` : elle peut coexister avec une future version
release (`fr.montemps`). Une APK debug compilée sur une autre machine est signée avec une autre clé :
il faut alors désinstaller l'ancienne (exporter d'abord une sauvegarde JSON).

## Compilation

Prérequis : JDK 17 ou plus récent, Android SDK (plateforme 36). Le wrapper Gradle télécharge Gradle 8.14.3.

```bash
echo "sdk.dir=/chemin/vers/android-sdk" > local.properties   # ou variable ANDROID_HOME
./gradlew :app:assembleDebug          # APK : app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # tests JVM + Robolectric (53 tests, régénère les captures)
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
│   └── report/             Calcul des bilans, formatage français
├── data/                   Room (base, DAO, triggers d'intégrité), DataStore, sauvegarde CSV/JSON
├── notification/           Notification de suivi, récepteurs (actions, démarrage, heure)
├── ui/                     Compose : today, history, reports, activities, settings, components
├── AppContainer.kt         Assemblage des dépendances (sans framework d'injection)
└── MonTempsApp.kt / MainActivity.kt
docs/ARCHITECTURE.md        Décisions, comportement temporel, inter-apps
docs/TEST_POCO.md           Procédure de test manuelle sur le téléphone
```

## Vérifications et limites

Voir [`docs/VERIFICATION.md`](docs/VERIFICATION.md) : ce qui a été réellement vérifié, comment, et
ce qui reste à valider sur l'appareil physique (aucun test n'a été fait sur un vrai POCO).
