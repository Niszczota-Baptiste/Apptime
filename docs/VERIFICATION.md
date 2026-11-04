# Vérifications effectuées (version 0.1.0)

Environnement : conteneur Linux sans KVM (aucun émulateur possible), JDK 21, Android SDK 36.
**Aucun test sur appareil physique ni sur émulateur.** Les tests Android ont tourné sous Robolectric
4.16 (framework Android 16 simulé sur la JVM).

## Résultats

| Vérification | Commande | Résultat |
|--------------|----------|----------|
| Compilation debug | `./gradlew :app:assembleDebug` | OK : APK de 13,8 Mo, `fr.montemps.debug`, targetSdk 36 |
| Compilation release (R8) | `./gradlew :app:assembleRelease` | OK : APK non signée de 1,9 Mo |
| Tests unitaires + Robolectric | `./gradlew :app:testDebugUnitTest` | **50/50 réussis** |
| Android Lint | `./gradlew :app:lintDebug` | 0 erreur ; 1 avertissement (`localeConfig` ignoré sous API 33, sans effet) |
| Permissions de l'APK | `aapt2 dump badging` | `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` uniquement (+ permission interne générée par AndroidX) |

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
| Notification : chronomètre natif, `when` = début, silencieuse, ongoing, catégorie, actions | `NotificationTest.startingShowsSilentChronometerNotificationWithFavorites` |
| Actions de notification (répétées) via la logique commune | `NotificationTest.notificationActionsSwitchAndStopThroughSharedLogic` |
| Masquage du nom sur l'écran verrouillé | `NotificationTest.hideNameOnLockScreenUsesPrivateVisibilityWithNeutralPublicVersion` |
| Notification balayée non republiée | `NotificationTest.dismissedNotificationIsNotRepostedUntilNextTransition` |
| Permission refusée puis accordée | `NotificationTest.trackingWorksWithoutNotificationPermission` |
| Redémarrage (récepteur déclaré + republication) | `NotificationTest.bootBroadcastRestoresNotificationFromDatabase` |

Limite de ces tests : sous Robolectric, les diffusions explicites vers le récepteur ne sont pas
routées à cause du suffixe `.debug` ; les tests extraient donc l'Intent réel de chaque
PendingIntent (en vérifiant cible, type et immutabilité) et appellent `onReceive` directement.

## Vérification visuelle

`ScreenshotTest` rend les vrais écrans (navigation par clics, données en base, horloge figée au
mercredi 4 novembre 2026 15:42) sur un gabarit de 412 × 892 dp, en clair, sombre et texte ×1,8.
Les PNG sont dans `docs/screenshots/` et ont été relus : lisibilité, état actif reconnaissable sans
la couleur (cadre épais, coche, « En cours »), état vide, refus de chevauchement, boîte « session
oubliée ». Correction apportée après relecture : libellés de la barre du bas tronqués proprement
en très grand texte.

`14-notification.png` est le rendu Android de la vue étendue de la notification. **La valeur du
chronomètre dans l'en-tête de cette image est un artefact** (horloge système simulée de
Robolectric) ; la valeur réelle (`when` = début de session, chronomètre activé) est vérifiée par
`NotificationTest`. Le rendu HyperOS réel (couleurs, actions visibles, écran verrouillé) n'a pas
pu être vérifié.

## Non vérifié / limites connues

- Comportement réel d'HyperOS : affichage sur l'écran verrouillé, nombre d'actions visibles,
  retrait éventuel de la notification lors du balayage dans les récentes, délai de
  `BOOT_COMPLETED`, gestion de batterie agressive. → `docs/TEST_POCO.md`.
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
- Pas d'annulation (« undo ») après suppression : une confirmation est demandée à la place.
- Restauration = remplacement complet (pas de fusion).
- Live Updates / Always-on Display : hors périmètre.

## Prochaines améliorations (hors périmètre de cette version)

1. Tester sur le POCO et ajuster selon les résultats de `docs/TEST_POCO.md`.
2. Widget d'écran d'accueil (Glance) et tuile de réglages rapides pour changer d'activité.
3. Promotion en « Live Update » Android 16 (`ProgressStyle` / notification promue) si HyperOS la prend en charge.
4. Annulation de suppression (snackbar) et édition du début de la session active.
5. Interface inter-apps protégée par permission de signature (voir `ARCHITECTURE.md`) et table `events`.
6. Objectifs hebdomadaires par activité et comparaison semaine précédente.
7. Signature release avec un keystore personnel et intégration continue (build + tests).
