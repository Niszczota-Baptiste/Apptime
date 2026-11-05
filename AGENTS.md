# Consignes pour les agents

- Projet Android « Mon Temps » (suivi du temps personnel), interface **entièrement en français**.
- Lire `docs/ARCHITECTURE.md` avant toute modification de la logique de suivi ou du modèle de données.
- Toute opération de démarrage / changement / arrêt (y compris « Annuler » et « Démarrer plus tôt »)
  passe par `domain/TimeTracker` ; toute correction d'historique par `domain/SessionEditor`.
  Ne jamais écrire dans Room directement depuis l'UI ou un récepteur.
- Le paquet `domain` reste en Kotlin pur (aucun import `android.*`).
- Notifications (suivi, rappels, résumé), widgets, tuile, montre Garmin, alarmes et sauvegarde
  automatique ne se mettent à jour que via `AppContainer.refreshOutputs()`
  (déjà appelé après chaque écriture) ; leurs actions passent par `TrackingIntents` → `TrackingActionReceiver`.
- Montre Garmin : le format des messages est décrit dans `docs/GARMIN.md` et partagé par
  `garmin/WatchProtocol.kt` (Android) et `garmin/source/` (Monkey C) — modifier les deux ensemble,
  garder `WatchProtocol.APP_ID` égal à l'identifiant de `garmin/manifest.xml`, et recompiler l'app
  montre au niveau de types strict (`monkeyc … -l 3`, voir `docs/GARMIN.md`).
- Interface sobre : pas d'icônes ni d'émojis pour les activités (nom + pastille de couleur).
- Changer le schéma Room = incrémenter la version, ajouter une migration et committer `app/schemas/`.
- Changer le format JSON = incrémenter `BackupCodec.CURRENT_VERSION` et garder la lecture des versions antérieures.
- Ne pas ajouter de service au premier plan, wake lock, permission réseau/stockage/localisation/santé,
  ni de composant exporté, sans justification documentée dans `docs/ARCHITECTURE.md`.
- Avant de livrer : `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
  Mettre à jour `docs/VERIFICATION.md` avec ce qui a réellement été vérifié.
