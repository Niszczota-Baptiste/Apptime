# Vérifications effectuées (version 0.1.0 : interface épurée, widgets, tuile, 10 améliorations d'usage)

Environnement : conteneur Linux sans KVM (aucun émulateur possible), JDK 21, Android SDK 36.
**Aucun test sur appareil physique ni sur émulateur.** Les tests Android ont tourné sous Robolectric
4.16 (framework Android 16 simulé sur la JVM).

## Résultats

| Vérification | Commande | Résultat |
|--------------|----------|----------|
| Compilation debug | `./gradlew :app:assembleDebug` | OK : APK de 14,3 Mo, `fr.montemps.debug`, targetSdk 36 |
| Compilation release (R8) | `./gradlew :app:assembleRelease` | OK : APK non signée de 2,0 Mo |
| Tests unitaires + Robolectric | `./gradlew :app:testDebugUnitTest` | **96/96 réussis** |
| Android Lint | `./gradlew :app:lintDebug` | 0 erreur ; 1 avertissement (`localeConfig` ignoré sous API 33, sans effet) |
| Permissions de l'APK | `aapt2 dump badging` | `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `POST_PROMOTED_NOTIFICATIONS` (permission normale des Live Updates) + permission interne générée par AndroidX. Widgets, tuile, alarmes inexactes et dossier de sauvegarde (SAF) n'en ajoutent aucune |

## Couverture des tests

| Exigence | Test(s) |
|----------|---------|
| Démarrage et arrêt | `TimeTrackerTest.startThenStop` |
| Changement sans trou ni chevauchement | `TimeTrackerTest.switchingEndsPreviousAtExactlyTheSameInstant` |
| Appui répété sur l'activité active | `TimeTrackerTest.tappingActiveActivityAgainDoesNothing` |
| Pause ≠ arrêt | `TimeTrackerTest.pauseIsATrackedActivityNotAStop` |
| Transitions concurrentes (300 appels parallèles) | `TimeTrackerTest.concurrentTransitionsNeverCreateSimultaneousSessions` |
| Action de notification périmée | `TimeTrackerTest.repeatedNotificationStopWithStaleSessionIdIsIgnored` |
| Triggers SQLite (2ᵉ session active, fin ≤ début) | `TimeTrackerTest.databaseRejectsSecondActiveSessionEvenIfLogicIsBypassed` |
| Reconstruction après recréation du processus / redémarrage | `TimeTrackerTest.activeSessionIsRebuiltAfterProcessRecreation` (base fichier fermée puis rouverte) |
| Horloge reculée | `TimeTrackerTest.clockSetBackBeforeLastRecordIsRejectedInsteadOfCreatingOverlap` |
| Session oubliée terminée à une heure passée | `TimeTrackerTest.forgottenSessionCanBeEndedAtAPastTime` |
| Répartition à minuit | `ReportCalculatorTest.sessionAcrossMidnightIsSplitBetweenDays` |
| Session sur plusieurs jours | `ReportCalculatorTest.sessionSpanningSeveralDaysFillsMiddleDaysCompletely`, `SessionEditorTest.multiDaySessionCanBeRecordedAndIsSplitInReports` |
| Heure d'été (23 h) / d'hiver (25 h) | `ReportCalculatorTest.springForwardDayLasts23Hours`, `fallBackDayLasts25Hours` |
| Changement de fuseau | `ReportCalculatorTest.timeZoneChangeRebucketsSameInstants` |
| Session active dans le bilan, journée partielle | `ReportCalculatorTest.activeSessionCountsUpToNowAndDayIsPartial` |
| Refus d'un chevauchement (y compris session active) | `SessionEditorTest.rejectsOverlapWithCompletedAndActiveSessions`, `updateCreatingOverlapIsRefusedAndLeavesDataUnchanged`, `SessionValidatorTest` |
| Validations de saisie | `SessionEditorTest.rejectsInvalidRanges`, `SessionValidatorTest.completedValidation` |
| Début de la session active verrouillé | `SessionEditorTest.activeSessionStartIsLockedButActivityAndNoteEditable` |
| Actualisation des bilans après correction | `SessionEditorTest.reportsRefreshAfterCorrection` (flux Room observé) |
| Renommage / archivage | `SessionEditorTest.renamingKeepsHistoryAttachedToSameId`, `TimeTrackerTest.archivedOrUnknownActivityIsRejected` |
| Restauration valide (aller-retour complet) | `BackupTest.exportThenRestoreRoundTrip` |
| Rejet de sauvegardes invalides sans modification | `BackupTest.invalidBackupsAreRejectedWithoutTouchingData`, `overlappingSessionsInBackupAreRejected` |
| CSV | `BackupTest.csvIsSpreadsheetFriendly` |
| Lecture d'une sauvegarde v1 (format actuel : v2) | `BackupTest.version1BackupIsStillReadable` |
| Widget « Suivi en cours » : activité, chronomètre natif (base monotone), Arrêter, favorites, total du jour, état inactif | `SurfacesTest.trackingWidgetFollowsTheActiveSessionWithANativeChronometer` |
| Widget « Activités » : ordre, activité active marquée | `SurfacesTest.activitiesWidgetListsShortcutsAndMarksTheActiveOne` |
| Clics dans les widgets → logique commune (source `widget`) | `SurfacesTest.widgetButtonsGoThroughTheSharedTrackingLogic` |
| Adaptation à la taille du widget | `SurfacesTest.smallSizesOnlyShowWhatFits` |
| Tuile : arrêt puis reprise de la dernière activité (source `tile`) | `SurfacesTest.quickSettingsTileStopsThenResumesTheLastActivity`, `TileModelTest` |
| Rafraîchissement de minuit (alarme inexacte, sans réveil) | `SurfacesTest.midnightRefreshIsScheduledOnlyWhenUseful` |
| Rappel de session longue : programmation, publication unique, « Continuer » | `ReminderTest.longSessionReminderIsScheduledPostedOnceAndAcknowledged` |
| Rappel annulé au changement d'activité ou à l'arrêt ; désactivable | `ReminderTest.switchingActivityCancelsTheReminderOfThePreviousSession`, `disabledReminderSchedulesNothing` |
| Notification : chronomètre natif, `when` = début, silencieuse, ongoing, catégorie, actions | `NotificationTest.startingShowsSilentChronometerNotificationWithFavorites` |
| Actions de notification (répétées) via la logique commune | `NotificationTest.notificationActionsSwitchAndStopThroughSharedLogic` |
| Masquage du nom sur l'écran verrouillé | `NotificationTest.hideNameOnLockScreenUsesPrivateVisibilityWithNeutralPublicVersion` |
| Notification balayée non republiée | `NotificationTest.dismissedNotificationIsNotRepostedUntilNextTransition` |
| **1. Annuler** un changement, un démarrage, un arrêt ; refus si autre chose a changé | `UndoAndBackdateTest.undoingASwitchReopensThePreviousSession`, `undoingAStartOrAStop`, `undoIsRefusedOnceSomethingElseChanged` |
| **2. Démarrer plus tôt** : découpe sans chevauchement, bornes (session en cours, fin précédente, futur) | `UndoAndBackdateTest.startingEarlierSplitsTheRunningSessionWithoutOverlap`, `startingEarlierIsBoundedByTheRunningAndPreviousSessions` |
| **3. Reprendre** : règle (interruptions successives, archivage), notification, widget, tuile | `InsightsTest.resumeTargetIsTheLastNonInterruptionActivity`, `ScheduledNotificationsTest.trackingNotificationRequestsLiveUpdateAndOffersResumeDuringABreak`, `SurfacesTest.duringABreakWidgetAndTileOfferToResumeThePreviousActivity` |
| **4. Trous** : périodes non suivies jusqu'à maintenant, seuil, session en cours | `InsightsTest.gapsAreUntrackedPeriodsUntilNowOnly` |
| **5. Objectifs** jour/semaine, jours atteints sur la semaine | `InsightsTest.goalsProgressPerDayAndOverAWeek` |
| **6. Résumé du soir** : heure, jour vide ignoré, une fois par jour, canal silencieux, ouvre les bilans | `ScheduledNotificationsTest.eveningSummaryIsSilentDailyAndSkipsEmptyDays`, `InsightsTest.eveningSummaryText` |
| **7. Rien n'est suivi** : délai, plage horaire, une fois par période, ouverture de l'app, retrait au démarrage, désactivé par défaut | `ScheduledNotificationsTest.idleReminderFiresOncePerIdlePeriodAndClearsWhenTrackingResumes`, `openingTheAppRestartsTheIdlePeriod`, `idleReminderIsOffByDefault`, `InsightsTest.idleReminderTimingWindowsAndOncePerPeriod` |
| **8. Live Update** : demande de promotion et critères documentés, réglage pour la désactiver | `ScheduledNotificationsTest.trackingNotificationRequestsLiveUpdateAndOffersResumeDuringABreak` |
| **9. Tendances** : comparaison au même moment, moyenne ; séries (objectif quotidien respecté) | `InsightsTest.weekComparisonAtTheSameMomentAndDailyAverage`, `streaksCountConsecutiveDaysAndRespectDailyGoals` |
| **10. Sauvegarde automatique** : fichier restaurable, 8 copies, échéance hebdomadaire, échec puis nouvel essai | `AutoBackupTest` (dossier simulé en mémoire) |
| Migration de la base v1 → v2 sans perte | `MigrationTest.version1DatabaseIsMigratedWithoutDataLoss` |
| Sauvegarde v3 (objectifs, interruptions, préférences) et lecture des v1/v2 | `BackupTest.exportThenRestoreRoundTrip`, `version1BackupIsStillReadable` |
| Permission refusée puis accordée | `NotificationTest.trackingWorksWithoutNotificationPermission` |
| Redémarrage (récepteur déclaré + republication) | `NotificationTest.bootBroadcastRestoresNotificationFromDatabase` |

Limite de ces tests : sous Robolectric, les diffusions explicites vers le récepteur ne sont pas
routées à cause du suffixe `.debug` ; les tests extraient donc l'Intent réel de chaque
PendingIntent (en vérifiant cible, type et immutabilité) et appellent `onReceive` directement.

## Vérification visuelle

`ScreenshotTest` rend les vrais écrans (navigation par clics, données en base, horloge figée au
mercredi 4 novembre 2026 15:42) sur un gabarit de 412 × 892 dp, en clair, en sombre et en texte ×1,8,
ainsi que les widgets (RemoteViews réellement appliquées par le gestionnaire de widgets simulé, posées
sur un fond neutre — le reste de l'écran d'accueil n'est pas simulé). Les PNG sont dans
`docs/screenshots/` et ont été relus :

| Fichier | Contenu |
|---------|---------|
| `01-aujourdhui`, `02-aujourdhui-sombre` | Activité en cours, chronomètre, raccourcis, bilan du jour |
| `03-historique`, `16-historique-sombre` | Sessions du jour, session à cheval sur minuit |
| `04-modifier-session`, `09-chevauchement-refuse` | Correction, refus d'un chevauchement |
| `05-bilan-jour`, `06-bilan-semaine`, `06b-bilan-semaine-page-entiere`, `17-bilan-semaine-sombre` | Bilans |
| `07-reglages`, `08-activites` | Réglages, gestion des activités |
| `10-aujourdhui-grand-texte` | Taille de texte ×1,8 (une colonne, chronomètre ajusté) |
| `11-premier-lancement` | État vide « Aucune activité en cours » |
| `12-session-oubliee`, `13-session-oubliee-correction` | Session oubliée |
| `14-notification`, `15-notification-rappel`, `26-notification-inactivite` | Notifications de suivi, de session longue et « rien n'est suivi » (vues construites par Android) |
| `19-ecran-accueil-widgets`, `20-ecran-accueil-widgets-sombre` | Widgets « Suivi en cours » et « Activités » |
| `21-aujourdhui-pause-page-entiere` | Pause en cours : « Reprendre Travail », objectifs du jour |
| `22-demarrer-plus-tot`, `23-annuler` | Appui long « Démarrer plus tôt », puis message « Annuler » |
| `24-combler-un-trou` | Saisie préremplie depuis une période « Non suivi » de l'historique |
| `05b-bilan-jour-page-entiere` | Objectifs du jour et séries en cours |
| `06b-bilan-semaine-page-entiere` | Comparaison au même moment, moyenne, objectifs, évolution, séries, vue par jour |
| `07b-reglages-page-entiere` | Réglages complets (Live Update, rappels, sauvegarde automatique) |
| `25-modifier-activite` | Interruption et objectif d'une activité |

Points vérifiés à la relecture : les dix raccourcis tiennent sur un écran, l'activité active est
reconnaissable sans la couleur (négatif + « En cours »), le chronomètre reste sur une ligne en très
grand texte, contrastes suffisants en clair comme en sombre, les états vides sont explicites.
Corrections faites après relecture : rangée des favorites coupée dans le widget (ligne d'infos
regroupée, favorites affichées seulement à partir de 170 dp), texte du rappel raccourci à une ligne.
Bug trouvé par les tests et corrigé : la comparaison de semaines comptait des sessions de la
semaine précédente postérieures au « même moment ».
Non capturé : le résumé du soir (style « texte long » mal rendu par le simulateur) ; son contenu est
vérifié par les tests.

`14-notification.png` : le chronomètre natif de l'en-tête affiche bien la durée de la session
(22:10), l'heure de début étant alignée sur l'horloge simulée de Robolectric. Le rendu HyperOS réel
(couleurs, actions visibles, écran verrouillé) n'a pas pu être vérifié.

## Non vérifié / limites connues

- Comportement réel d'HyperOS : affichage sur l'écran verrouillé, nombre d'actions visibles,
  retrait éventuel de la notification lors du balayage dans les récentes, délai de
  `BOOT_COMPLETED`, gestion de batterie agressive, réglage « Démarrage automatique » (peut bloquer le
  redémarrage, le rafraîchissement de minuit et le rappel). → `docs/TEST_POCO.md`.
- Widgets et tuile non vérifiés sur le lanceur et le centre de contrôle d'HyperOS : prise en charge de
  l'ajout direct (`requestPinAppWidget`, `requestAddTileService`), tailles réelles des cellules,
  affichage du sous-titre de la tuile, fonctionnement des boutons de widget après un arrêt forcé.
- Le chronomètre des widgets et de la notification utilise le format du système (« 22:10 »,
  « 1:23:45 »), légèrement différent de l'app (« 00:22:10 »).
- Rappels, résumé, rafraîchissement de minuit et sauvegarde automatique reposent sur des alarmes
  inexactes : ils peuvent arriver avec quelques minutes de retard (choix volontaire : ni permission
  spéciale ni réveil de l'appareil).
- **Live Update** : la demande de promotion et les critères documentés sont vérifiés, mais la
  promotion effective dépend d'Android 16, des réglages de l'utilisateur et d'HyperOS (qui peut
  appliquer ses propres critères) ; la méthode `hasPromotableCharacteristics()` de la plateforme
  n'a pas pu être évaluée dans le simulateur.
- **Sauvegarde automatique** : la logique (échéance, rotation, erreurs) est testée avec un dossier
  simulé ; l'écriture réelle via le sélecteur de documents (permission persistante, création et
  suppression de fichiers) n'a pas pu être exercée hors appareil.
- Une session à cheval sur minuit compte pour les deux jours dans les séries (règle « au moins
  1 min suivie ce jour-là »).
- Sur Android 14+, l'utilisateur peut balayer la notification (hors écran verrouillé) : c'est
  voulu ; elle n'est pas republiée de force.
- Le chronomètre natif s'affiche dans l'en-tête de la notification ; le texte affiche « En cours
  depuis HH:MM ». La durée n'est pas intégrée au texte (cela imposerait une republication chaque seconde).
- Le début d'une session **en cours** n'est pas modifiable : arrêter le suivi puis corriger la
  session terminée.
- Si l'heure système recule avant le début de la session en cours, « Arrêter » est refusé tant que
  l'heure n'est pas revenue après ce début (message explicite) ; on peut supprimer la session.
- Les bilans utilisent le fuseau courant : après un voyage, les jours passés sont redécoupés selon
  le nouveau fuseau (les durées totales ne changent pas).
- Pas d'annulation (« undo ») après *suppression* dans l'historique (une confirmation est demandée) ;
  l'annulation existe pour les changements d'activité et l'arrêt.
- Les nouvelles couleurs par défaut des activités ne s'appliquent qu'à une installation neuve ;
  sur une installation existante, les couleurs se changent dans Réglages → Gérer les activités.
- Restauration = remplacement complet (pas de fusion).
- Always-on Display : hors périmètre.

## Prochaines améliorations (hors périmètre de cette version)

1. Tester sur le POCO et ajuster selon les résultats de `docs/TEST_POCO.md` (sections 15 à 20 pour
   les nouveautés).
2. Annulation après suppression dans l'historique et édition du début de la session active.
3. Interface inter-apps protégée par permission de signature (voir `ARCHITECTURE.md`) et table `events` ;
   les widgets des futures apps (Coréen, Sport, Création) pourront suivre le même modèle que `surfaces/`.
4. Signature release avec un keystore personnel et intégration continue (build + tests).
