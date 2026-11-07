# Vérifications effectuées (version 0.1.0 : interface épurée, widgets, tuile, 10 améliorations d'usage, montre Garmin, sous-catégories, coucher et réveil automatiques, réglages repliables)

Téléphone cible : Redmi Note 17 Pro 5G (HyperOS 3, Android 16, 6 Go de RAM) — voir
« Changement de téléphone » plus bas. Montre : Garmin Forerunner 965 — voir « Montre Garmin ».
Environnement : conteneur Linux sans KVM (aucun émulateur possible), JDK 21, Android SDK 36.
**Aucun test sur appareil physique ni sur émulateur.** Les tests Android ont tourné sous Robolectric
4.16 (framework Android 16 simulé sur la JVM).

## Résultats

| Vérification | Commande | Résultat |
|--------------|----------|----------|
| Compilation debug | `./gradlew :app:assembleDebug` | OK : APK de 16,5 Mo, `fr.montemps.debug`, targetSdk 36 (SDK Connect IQ et Health Connect inclus) |
| Compilation release (R8) | `./gradlew :app:assembleRelease` | OK : APK non signée de 2,3 Mo (règles R8 : classes du SDK Connect IQ conservées) |
| Tests unitaires + Robolectric | `./gradlew :app:testDebugUnitTest` | **148/148 réussis** (22 classes) |
| Android Lint | `./gradlew :app:lintDebug` | 0 erreur ; 1 avertissement (`localeConfig` ignoré sous API 33, sans effet) |
| Permissions de l'APK | `aapt2 dump permissions` | `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `POST_PROMOTED_NOTIFICATIONS` (permission normale des Live Updates), `health.READ_EXERCISE` (import des séances, demandée à l'utilisateur) + permission interne générée par AndroidX. Widgets, tuile, alarmes inexactes (coucher et réveil compris), dossier de sauvegarde (SAF) et SDK Connect IQ n'en ajoutent aucune ; inchangées par les sous-catégories et le coucher/réveil |
| Manifeste fusionné | `aapt2 dump xmltree` | Service du SDK Connect IQ `IQGarminBindingService` présent avec `enabled=false` (activé seulement par « Relier la montre ») ; aucun composant ajouté ni exporté par cette version |
| Pages mémoire de 16 Ko | `zipalign -c -P 16 4` | OK sur l'APK debug |
| App montre (Connect IQ) | `monkeyc -f garmin/monkey.jungle -d fr965 -l 3` (compilateur officiel 9.3.0) | **Compile sans erreur ni avertissement au niveau de types strict** (sous-menu des sous-catégories compris) ; `.prg` de 119 Ko. Définition de la Forerunner 965 reconstituée à partir des données du SDK (le fichier officiel exige un compte Garmin) ; aucun lancement (pas de simulateur ni de montre) |

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
| Lecture d'une sauvegarde v1 (format actuel : v4) | `BackupTest.version1BackupIsStillReadable` |
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
| Migration de la base v1 → v2 → v3 sans perte (bases créées avec les schémas exportés v1 et v2, validées par Room) | `MigrationTest.version1DatabaseIsMigratedWithoutDataLoss`, `version2DatabaseGetsSubCategoriesRenamedTravelAndSleep`, `version2RenamedTravelAndExistingSleepAreLeftAlone` |
| Sauvegarde v4 (sous-catégories, sessions précisées, coucher/réveil) et lecture des v1 à v3 | `BackupTest.exportThenRestoreRoundTrip`, `version1BackupIsStillReadable` |
| Sauvegarde refusée si une session cite une sous-catégorie inconnue ou d'une autre activité | `BackupTest.invalidBackupsAreRejectedWithoutTouchingData` |
| **Sous-catégories** : valeurs par défaut (Déplacement, Sommeil, Repas sans sous-catégorie) | `SubActivitiesTest.freshInstallHasDefaultSubCategoriesDeplacementAndSommeil` |
| **Sous-catégories** : choisir démarre, même choix sans effet, autre choix = changement sans trou, session sans précision précisée sur place | `SubActivitiesTest.choosingASubStartsRefinesOrSwitches`, `aSessionWithoutSubIsRefinedInPlace` |
| **Sous-catégories** : refus d'une sous-catégorie inconnue, retirée ou d'une autre activité (logique, saisie, trigger SQLite) | `SubActivitiesTest.invalidOrRetiredSubIsRejected`, `editorAcceptsOnlyASubOfTheSessionActivity`, `databaseRejectsASubOfAnotherActivityEvenIfLogicIsBypassed` |
| **Sous-catégories** : « Reprendre » garde la sous-catégorie (sauf si elle a été retirée) | `SubActivitiesTest.resumeAfterABreakKeepsTheSubCategory`, `SurfacesTest.surfacesShowTheSubCategoryAndResumeIt` |
| **Sous-catégories** : ajout, renommage, retrait puis rétablissement avec le même identifiant | `SubActivitiesTest.catalogAddsRenamesAndRestoresSubCategories` |
| **Sous-catégories** : bilans détaillés (« sans précision » à part) ; CSV | `SubActivitiesTest.reportsSplitAnActivityBySubCategory`, `BackupTest.csvIsSpreadsheetFriendly` |
| **Sous-catégories** : widget « Activités » → choix dans l'app, rien ne démarre avant ; nom complet dans le widget et la tuile | `SurfacesTest.anActivityWithSubCategoriesOpensTheChooserInTheApp`, `surfacesShowTheSubCategoryAndResumeIt` |
| **Sous-catégories** : séance importée → sous-catégorie d'après le type | `WorkoutImportTest.subCategoryIsSuggestedFromTheWorkoutType` |
| **Coucher et réveil** : échéances (heure, jours, changements d'heure), délai de grâce, une seule fois, rien de rétroactif, libellés des jours | `SleepScheduleTest` (5 tests) |
| **Coucher** : réglage comme depuis l'écran, alarme inexacte (`RTC`), Sommeil démarré à l'heure prévue malgré une alarme en retard, notification silencieuse, échéance suivante, « Annuler » | `SleepTest.bedtimeStartsSleepAtTheScheduledTimeAndCanBeUndone` |
| **Réveil** : Sommeil arrêté à l'heure prévue, durée, favorites, « Je dors encore » | `SleepTest.wakeStopsSleepAtTheScheduledTimeAndCanBeUndone` |
| **Coucher et réveil** : le suivi manuel l'emporte ; réglage jamais rétroactif ; jours ; désactivation ; Sommeil archivée rétablie | `SleepTest.whatTheUserDidMeanwhileAlwaysWins`, `changingTheSettingIsNeverRetroactiveAndDisablingCancels`, `anArchivedSleepActivityIsRestored` |
| Permission refusée puis accordée | `NotificationTest.trackingWorksWithoutNotificationPermission` |
| **Montre** : format des messages (commandes, heure d'origine, commandes trop anciennes, état, envoi seulement si l'état change) | `WatchProtocolTest` (6 tests) |
| **Montre** : sous-catégories dans les deux sens (commande, nom complet, sous-menus, renommage renvoyé) ; refus d'une sous-catégorie retirée | `WatchProtocolTest.subCategoriesTravelBothWays`, `WatchIntegrationTest.subCategoryChosenOnTheWatchIsAppliedOrRefused` |
| **Montre** : liaison coupée et service du SDK désactivé par défaut, activation | `WatchIntegrationTest.linkIsOffAndTheSdkServiceDisabledUntilTheUserEnablesIt` |
| **Montre** : commande → récepteur → `TimeTracker` (source `watch`) → état renvoyé ; commande répétée sans effet | `WatchIntegrationTest.commandFromTheWatchSwitchesActivityAndRepliesWithTheNewState`, `stateIsSentWhenItChangesOnly` |
| **Montre** : commande hors ligne appliquée à son heure ; refus si le téléphone a changé depuis ; reprise | `WatchIntegrationTest.offlineCommandIsAppliedAtItsOriginalTime`, `outdatedOfflineCommandIsRefusedWithAMessage`, `resumeAfterABreakReturnsToTheInterruptedActivity` |
| **Montre** : vrai SDK Connect IQ sans Garmin Connect installé | `WatchIntegrationTest.realSdkReportsThatGarminConnectIsMissing` |
| **Séances** : propositions (créneau libre, déjà importée, chevauchement, doublon entre apps, séance en cours ou trop courte), note | `WorkoutImportTest` (6 tests) |
| **Séances** : états Health Connect, import en Sport (source `healthconnect`), activité choisie conservée | `WorkoutImportViewModelTest` (3 tests, source simulée) |
| Redémarrage (récepteur déclaré + republication) | `NotificationTest.bootBroadcastRestoresNotificationFromDatabase` |

Limite de ces tests : sous Robolectric, les diffusions explicites vers le récepteur ne sont pas
routées à cause du suffixe `.debug` ; les tests extraient donc l'Intent réel de chaque
PendingIntent (en vérifiant cible, type et immutabilité) et appellent `onReceive` directement.

## Vérification visuelle

`ScreenshotTest` rend les vrais écrans (navigation par clics, données en base, horloge figée au
mercredi 4 novembre 2026 15:42) sur un gabarit de 412 × 892 dp, en clair, en sombre et en texte ×1,8
(celui-ci sur 393 × 852 dp, le cas le plus étroit du Redmi Note 17 Pro 5G),
ainsi que les widgets (RemoteViews réellement appliquées par le gestionnaire de widgets simulé, posées
sur un fond neutre — le reste de l'écran d'accueil n'est pas simulé). Les PNG sont dans
`docs/screenshots/` et ont été relus :

| Fichier | Contenu |
|---------|---------|
| `01-aujourdhui`, `02-aujourdhui-sombre` | Activité en cours, chronomètre, raccourcis, bilan du jour |
| `03-historique`, `16-historique-sombre` | Sessions du jour, session à cheval sur minuit |
| `04-modifier-session`, `09-chevauchement-refuse` | Correction, refus d'un chevauchement |
| `05-bilan-jour`, `06-bilan-semaine`, `06b-bilan-semaine-page-entiere`, `17-bilan-semaine-sombre` | Bilans |
| `07-reglages`, `08-activites` | Réglages (catégories repliées), gestion des activités |
| `10-aujourdhui-grand-texte` | Taille de texte ×1,8 sur 393 dp (une colonne, chronomètre ajusté, intitulés entiers) |
| `11-premier-lancement` | État vide « Aucune activité en cours » |
| `12-session-oubliee`, `13-session-oubliee-correction` | Session oubliée |
| `14-notification`, `15-notification-rappel`, `26-notification-inactivite` | Notifications de suivi, de session longue et « rien n'est suivi » (vues construites par Android) |
| `19-ecran-accueil-widgets`, `20-ecran-accueil-widgets-sombre` | Widgets « Suivi en cours » et « Activités » |
| `21-aujourdhui-pause-page-entiere` | Pause en cours : « Reprendre Travail », objectifs du jour |
| `22a-demarrer-plus-tot-sous-categorie`, `22-demarrer-plus-tot`, `23-annuler` | Appui long sur Sport : choix de la sous-catégorie, puis « Démarrer plus tôt », puis message « Annuler » |
| `29-choix-sous-categorie`, `30-aujourdhui-sous-categorie` | Appui sur Sport : Muscu / Foot / Course / Autre / Sans précision ; « Sport · Muscu » en cours |
| `31-gerer-sous-categories` | Gestion des sous-catégories de Travail (renommer, retirer, ajouter) |
| `32-notification-coucher`, `33-notification-reveil` | Notifications du coucher (« Annuler ») et du réveil (favorites, « Je dors encore ») |
| `24-combler-un-trou` | Saisie préremplie depuis une période « Non suivi » de l'historique |
| `05b-bilan-jour-page-entiere` | Objectifs du jour et séries en cours |
| `06b-bilan-semaine-page-entiere` | Comparaison au même moment, moyenne, objectifs, évolution, séries, vue par jour |
| `07b-reglages-page-entiere` | Catégories Notification, Sommeil (coucher et réveil activés) et Rappels dépliées |
| `25-modifier-activite` | Interruption et objectif d'une activité |
| `27-reglages-montre-garmin` | Catégorie « Montre Garmin » dépliée (liaison active, état de la montre simulé) |
| `28-seances-sportives` | Import des séances : cochée, déjà dans l'historique, chevauchement (séances simulées) |

Points vérifiés à la relecture : les onze raccourcis (Sommeil compris) tiennent sur un écran, l'activité active est
reconnaissable sans la couleur (négatif + « En cours »), le chronomètre reste sur une ligne en très
grand texte, contrastes suffisants en clair comme en sombre, les états vides sont explicites.
Corrections faites après relecture : rangée des favorites coupée dans le widget (ligne d'infos
regroupée, favorites affichées seulement à partir de 170 dp), texte du rappel raccourci à une ligne.
À 393 dp en texte ×1,8 : le titre « Activités » se coupait au milieu du mot (« Activité / s ») et
l'onglet « Aujourd'hui » était tronqué → l'indication passe sous le titre (tous les en-têtes de
section), les libellés de la barre du bas rétrécissent légèrement au lieu d'être coupés. À taille
normale, les captures sont identiques au pixel près avant et après la correction.
Bug trouvé par les tests et corrigé : la comparaison de semaines comptait des sessions de la
semaine précédente postérieures au « même moment ».
Sous-catégories et coucher/réveil, corrections après relecture et tests : les choix de
sous-catégorie (et les durées de « Démarrer plus tôt ») étaient en gris secondaire, comme
« Sans précision » → couleur du texte principal ; bugs trouvés par les nouveaux tests et corrigés :
(1) si Sommeil avait été archivée, le coucher automatique échouait sans rien dire (recréation
refusée car le nom existe) → Sommeil est désarchivée ; (2) restaurer une sauvegarde pouvait
déclencher un coucher rétroactif (échéance des 2 dernières heures) → coucher et réveil sont marqués
traités jusqu'à la restauration. La capture du widget « Activités » a été agrandie (6 rangées pour
11 activités).
Non capturé : le résumé du soir (style « texte long » mal rendu par le simulateur) ; son contenu est
vérifié par les tests.

`14-notification.png` : le chronomètre natif de l'en-tête affiche bien la durée de la session
(22:10), l'heure de début étant alignée sur l'horloge simulée de Robolectric. Le rendu HyperOS réel
(couleurs, actions visibles, écran verrouillé) n'a pas pu être vérifié.

## Changement de téléphone : Redmi Note 17 Pro 5G

Fiche technique relevée (page Xiaomi Inde et fiches de presse, novembre 2026) : HyperOS 3 sur
Android 16, écran 6,83" 1280 × 2772 px (≈ 447 ppi), Snapdragon 6s Gen 4 ; modèle de l'utilisateur
6 Go de RAM / 256 Go.

| Point | Constat | Conséquence |
|-------|---------|-------------|
| Système | Android 16 / HyperOS 3, comme la cible précédente | Aucun changement de code : targetSdk 36, permissions, notifications, alarmes, widgets et tuile identiques |
| Écran | 1280 px de large → entre ≈ 393 et 465 dp selon la densité choisie par HyperOS | Rendu vérifié à 393 dp (taille normale et ×1,8) : deux défauts d'affichage en très grand texte corrigés (voir plus haut) |
| Mémoire | 6 Go : HyperOS ferme plus vite les applications en arrière-plan | Rien à changer : le suivi ne dépend pas d'un processus actif (horodatages en base, chronomètre natif, alarmes reprogrammées au démarrage). Étape « Tout effacer » ajoutée à la procédure (section 7) |
| Pages mémoire de 16 Ko | 2 bibliothèques natives AndroidX dans l'APK (`graphics-path`, compteur DataStore) | Vérifié : segments ELF alignés sur 0x4000 et `zipalign -c -P 16` réussi → compatible si le téléphone utilise des pages de 16 Ko |
| Haut de l'écran | HyperOS 3.1 annonce la prise en charge de l'API Live Updates d'Android 16 (« Hyper Island »), sans code propre à Xiaomi — source : presse spécialisée, pas de documentation Xiaomi officielle trouvée | Aucune adaptation : l'app utilise déjà l'API standard. Rendu à vérifier sur l'appareil (section 20) |
| Données | Sauvegarde Android et transfert d'appareil exclus (`allowBackup=false`, `data_extraction_rules.xml`) | Changer de téléphone = « Créer une sauvegarde » puis « Restaurer une sauvegarde » (README) |
| Processeur, stockage | Sans incidence pour une app de cette taille | — |

## Montre Garmin (Forerunner 965)

Voir `docs/GARMIN.md`. Vérifié ici : tests Android ci-dessus, compilation stricte de l'app montre,
manifeste fusionné. Le SDK Android (`ciq-companion-app-sdk` 2.4.0) a été inspecté : aucune
permission, `<queries>` vers Garmin Connect, un service exporté qui vérifie la signature de
l'appelant avant de transmettre (seule voie écoutée pour les commandes), un récepteur dynamique
sans vérification (non utilisé pour les commandes). Non vérifié, faute de matériel : rendu et
fonctionnement de l'app sur la montre, liaison Bluetooth réelle, réception en arrière-plan,
installation par USB, présence des séances Garmin dans Health Connect, effet de `setLocalOnly`
sur Garmin Connect, sous-menu des sous-catégories sur l'écran de la montre. → `docs/TEST_TELEPHONE.md`, section 22.

## Non vérifié / limites connues

- Comportement réel d'HyperOS : affichage sur l'écran verrouillé, nombre d'actions visibles,
  retrait éventuel de la notification lors du balayage dans les récentes, délai de
  `BOOT_COMPLETED`, gestion de batterie agressive, réglage « Démarrage automatique » (peut bloquer le
  redémarrage, le rafraîchissement de minuit et le rappel). → `docs/TEST_TELEPHONE.md`.
- Widgets et tuile non vérifiés sur le lanceur et le centre de contrôle d'HyperOS : prise en charge de
  l'ajout direct (`requestPinAppWidget`, `requestAddTileService`), tailles réelles des cellules,
  affichage du sous-titre de la tuile, fonctionnement des boutons de widget après un arrêt forcé.
- Le chronomètre des widgets et de la notification utilise le format du système (« 22:10 »,
  « 1:23:45 »), légèrement différent de l'app (« 00:22:10 »).
- Rappels, résumé, coucher et réveil automatiques, rafraîchissement de minuit et sauvegarde
  automatique reposent sur des alarmes inexactes : ils peuvent arriver avec quelques minutes de
  retard (choix volontaire : ni permission spéciale ni réveil de l'appareil). Pour le coucher et le
  réveil, l'heure enregistrée est l'heure prévue ; seule la notification peut être en retard. Le
  retard réel sous HyperOS (écran éteint, économiseur, nuit entière) reste à mesurer
  (`docs/TEST_TELEPHONE.md`, section 25).
- Sous-catégories : les favorites de la notification et la tuile démarrent « sans précision » (pas
  de place pour un second choix) ; le widget « Activités » ouvre l'app sur le choix.
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
  sur une installation existante, les couleurs se changent dans Réglages → Activités → Gérer les activités.
- Restauration = remplacement complet (pas de fusion).
- Always-on Display : hors périmètre.

## Prochaines améliorations (hors périmètre de cette version)

1. Tester sur le Redmi et la Forerunner 965 et ajuster selon les résultats de `docs/TEST_TELEPHONE.md`
   (sections 15 à 20 et 23 à 25 pour les nouveautés, 22 pour la montre).
2. Montre : « Annuler » depuis le poignet, complication pour cadran (API 4.2+), séance Garmin
   terminée → proposition d'import immédiate (événement « activité terminée » de la montre).
3. Annulation après suppression dans l'historique et édition du début de la session active.
4. Interface inter-apps protégée par permission de signature (voir `ARCHITECTURE.md`) et table `events` ;
   les widgets des futures apps (Coréen, Sport, Création) pourront suivre le même modèle que `surfaces/`.
5. Signature release avec un keystore personnel et intégration continue (build + tests).
