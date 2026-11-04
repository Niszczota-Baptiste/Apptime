# Consignes pour les agents

- Projet Android « Mon Temps » (suivi du temps personnel), interface **entièrement en français**.
- Lire `docs/ARCHITECTURE.md` avant toute modification de la logique de suivi ou du modèle de données.
- Toute opération de démarrage / changement / arrêt passe par `domain/TimeTracker` ; toute correction
  d'historique par `domain/SessionEditor`. Ne jamais écrire dans Room directement depuis l'UI ou un récepteur.
- Le paquet `domain` reste en Kotlin pur (aucun import `android.*`).
- Changer le schéma Room = incrémenter la version, ajouter une migration et committer `app/schemas/`.
- Changer le format JSON = incrémenter `BackupCodec.CURRENT_VERSION` et garder la lecture des versions antérieures.
- Ne pas ajouter de service au premier plan, wake lock, permission réseau/stockage/localisation sans
  justification documentée dans `docs/ARCHITECTURE.md`.
- Avant de livrer : `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
  Mettre à jour `docs/VERIFICATION.md` avec ce qui a réellement été vérifié.
