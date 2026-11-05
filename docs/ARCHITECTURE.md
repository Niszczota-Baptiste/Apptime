# Architecture et décisions

## Vue d'ensemble

Un seul module Gradle (`app`), découpé en paquets à responsabilités nettes. La taille actuelle
du projet ne justifie pas une modularisation Gradle ; les frontières sont prêtes pour la faire
plus tard (le paquet `domain` n'importe rien d'Android).

```
ui (Compose, ViewModel, StateFlow)
   │  appelle
   ▼
domain : TimeTracker · SessionEditor · ActivityCatalog · ReportCalculator   ← Kotlin pur
   │  via le port TrackingStore
   ▼
data : RoomTrackingStore (Room) · PreferencesRepository (DataStore) · BackupService
notification : NotificationController → TrackingNotifier ; récepteurs → TimeTracker
```

- **Logique centralisée.** L'écran « Aujourd'hui », les actions de notification et (plus tard)
  les apps compagnons passent par `TimeTracker` (`TrackingCommands`). Toute écriture passe par
  un `WriteLock` partagé + une transaction Room, puis déclenche `ChangeListener` qui resynchronise
  la notification.
- **Injection manuelle** (`AppContainer`) : une instance par processus.

## Modèle de données

| Table | Colonnes principales |
|-------|----------------------|
| `activities` | `id` (texte stable), `name`, `color` (ARGB), `icon` (inutilisé), `sort_order`, `archived`, `interruption`, `goal_minutes`, `goal_period` (`day`/`week`) |
| `sessions` | `id`, `activity_id` → activities (RESTRICT), `start_utc_ms`, `end_utc_ms` (NULL = en cours), `zone_id`, `note`, `start_elapsed_ms`, `start_boot_count`, `source` |

- Intervalles **semi-ouverts** `[début, fin)` : une session peut commencer à l'instant exact où la
  précédente se termine (changement A → B sans trou ni chevauchement).
- **Triggers SQLite** (défense en profondeur) : refus d'une seconde session active et d'une fin
  ≤ début, même si la logique applicative était contournée.
- Identifiants d'activités par défaut : `work`, `driving`, `break`, `meal`, `korean`, `sport`,
  `writing`, `music`, `free_time`, `other`. Activités créées : UUID.
- Schéma Room exporté dans `app/schemas/` (base des migrations). **Version 2** : `interruption`,
  `goal_minutes`, `goal_period` (migration `MIGRATION_1_2` : données conservées, Pause et Repas
  deviennent des interruptions ; testée sur une base créée avec le schéma v1).

### Renommage d'une activité

Le nom n'est stocké que dans `activities`. Les sessions référencent l'identifiant. **Renommer une
activité renomme donc tout son historique** (bilans passés, export CSV). C'est le comportement
attendu pour corriger une faute ou préciser un libellé. Pour distinguer une activité réellement
nouvelle, il faut en créer une autre et archiver l'ancienne : l'archivage la retire des
raccourcis mais conserve ses sessions, visibles dans l'historique avec la mention « (archivée) ».

## Règles métier implémentées

1. Une seule session active (logique + trigger).
2. Appuyer sur une activité démarre son suivi.
3. A → B : fin de A et début de B au même instant, dans une transaction.
4. Réappuyer sur l'activité active : aucun effet (`NoChange`).
5. « Arrêter le suivi » laisse ensuite du temps non suivi.
6. « Pause » est une activité comme une autre.
7. Le temps non suivi n'est jamais attribué automatiquement ; les bilans l'affichent à part.
8. Doubles appuis / actions répétées : idempotence (`NoChange`), verrou d'écriture, et l'action
   « Arrêter » de la notification porte l'identifiant de la session visée (une action périmée
   n'arrête pas une session plus récente).
9. Chaque transition est atomique (transaction Room).
10. Horloge reculée sous la dernière transition enregistrée : la transition est **refusée** avec
    un message (plutôt que créer un chevauchement ou inventer une heure).
11. **Annuler** (`TimeTracker.undo`) : supprime la session démarrée et rouvre celle qui venait de
    se terminer, dans une transaction. Refusé si quoi que ce soit a changé depuis (autre
    transition, correction, session ajoutée après) : jamais d'état incohérent.
12. **Démarrer plus tôt** (`TimeTracker.switchToAt`) : l'instant choisi doit être passé, strictement
    après le début de la session en cours, ou au plus tôt à la fin de la dernière session. La session
    en cours se termine à cet instant ; l'horloge monotone de départ est ramenée d'autant.
13. **Reprendre** (`ResumeRule`) : pendant une activité marquée « interruption », l'app propose
    l'activité de la dernière session terminée qui n'est ni archivée ni elle-même une interruption.

## Temps : instants, fuseaux, horloge monotone

- **Instants** : millisecondes UTC depuis l'époque Unix ; JSON en ISO-8601 (`…Z`). Toutes les
  durées sont reconstruites à partir des instants de début/fin ; aucun compteur n'est incrémenté.
- **Fuseau** : celui du téléphone au moment de l'affichage. Il sert à afficher les heures et à
  découper jours (minuit → minuit) et semaines (lundi → lundi, ISO). Le fuseau de démarrage de
  chaque session est enregistré (`zone_id`) à titre informatif et dans l'export CSV.
- **Monotone** : `elapsedRealtime` + `BOOT_COUNT` au démarrage de la session. Pendant un même
  démarrage, l'écart entre durée calendaire et durée monotone révèle une modification de l'heure
  système : l'écran « Aujourd'hui » affiche alors un avertissement. Les durées restent calculées
  sur l'heure calendaire (seule mesure valable après un redémarrage) ; l'utilisateur corrige si besoin.
- **Saisie manuelle** : heures locales converties dans le fuseau courant. Heure inexistante
  (passage à l'heure d'été) → décalée vers l'avant ; heure ambiguë (retour à l'heure d'hiver) →
  premier décalage. Règles standard de `java.time`.

### Comportements en situation

| Situation | Comportement |
|-----------|--------------|
| App en arrière-plan | Rien ne tourne. La notification (chronomètre natif) avance seule. L'écran se met à jour au retour. |
| Processus détruit par Android | Aucune perte : la session active est en base. Au prochain lancement (app, action de notification, démarrage), la notification est resynchronisée. La notification déjà affichée reste en place et son chronomètre continue. |
| App retirée des récentes | Équivalent à une destruction de processus sur Android standard : la notification et la session restent. Certaines surcouches peuvent aussi retirer la notification ; la session, elle, est intacte. |
| Redémarrage du téléphone | `BOOT_COMPLETED` → republication de la notification, mise à jour des widgets (base du chronomètre recalculée) et reprogrammation des alarmes depuis la base. À la réouverture, l'app signale la session (« le téléphone a redémarré depuis ») et propose Continuer / Corriger l'heure de fin. Sur HyperOS, ceci suppose que le « Démarrage automatique » de l'app soit autorisé. |
| Arrêt forcé (Paramètres → Forcer l'arrêt) | Android retire les notifications, **annule les alarmes** et **bloque les diffusions** (y compris `BOOT_COMPLETED`) jusqu'à la prochaine ouverture manuelle. Les widgets restent affichés avec leur dernier état. La session reste en base ; tout est réaligné à l'ouverture, avec la même proposition de correction si elle semble oubliée. |
| Changement de fuseau | Durées inchangées (instants UTC). Les heures affichées et le découpage des jours suivent le nouveau fuseau ; `TIMEZONE_CHANGED` met à jour le texte « En cours depuis … » de la notification. |
| Correction de l'heure système | Durées calculées sur la nouvelle heure ; avertissement si l'écart avec l'horloge monotone dépasse 2 min. Si l'heure recule avant la dernière transition, les changements sont refusés tant que l'heure n'est pas cohérente (la session en cours peut être supprimée, ou corrigée après coup). |
| Session à cheval sur minuit | Un seul enregistrement ; les bilans répartissent la durée entre les jours. L'historique l'affiche sur chaque jour concerné (« Dont X ce jour »). |
| Plusieurs jours | Idem, les jours intermédiaires sont couverts à 100 %. |
| Heure d'été / d'hiver | Les journées durent 23 h ou 25 h ; les bilans utilisent la durée réelle et le signalent. |

### Pourquoi ni service au premier plan ni wake lock

Le suivi ne nécessite aucun calcul continu : deux horodatages suffisent, et le chronomètre de
notification est animé par le système (`setUsesChronometer` + `setWhen`). Aucun type de service au
premier plan ne correspond honnêtement à un chronomètre manuel (le seul candidat serait
`specialUse`, sans justification réelle ici). Depuis Android 14, une notification « ongoing » ordinaire
peut de toute façon être balayée par l'utilisateur (sauf écran verrouillé) : l'app respecte ce choix
et ne la republie pas pour cette session ; elle réapparaît au changement d'activité suivant, ou via
« Réafficher » (écran Aujourd'hui ou Réglages). Aucune republication périodique.

## Notification

- Canal « Suivi en cours », importance *par défaut* sans son ni vibration (`setSilent`,
  `setOnlyAlertOnce`) pour figurer dans la liste normale et sur l'écran verrouillé.
- Catégorie `stopwatch`, `setOngoing(true)`, `setLocalOnly(true)`.
- Actions : jusqu'à 2 favorites + « Arrêter ». **Une favorite déjà active est masquée** (elle
  n'aurait aucun effet). Le toucher sur la notification ouvre l'app.
- Écran verrouillé : `VISIBILITY_PUBLIC` par défaut ; avec « Masquer le nom », `VISIBILITY_PRIVATE`
  + version publique « Suivi en cours » avec le chronomètre. L'affichage effectif dépend des
  réglages Android/HyperOS (notifications sur écran verrouillé, contenu sensible).
- Autorisation refusée : le suivi fonctionne normalement dans l'app ; une carte propose
  d'autoriser (demande système une seule fois, puis renvoi vers les réglages).
- PendingIntent explicites, immuables, vers un récepteur **non exporté**.
- Pas de notification plein écran ni de faux appel.
- **Live Update (Android 16)** : la notification demande sa promotion (`setRequestPromotedOngoing`,
  permission normale `POST_PROMOTED_NOTIFICATIONS`) ; elle remplit les critères documentés (en
  cours, titre, pas de couleur de fond ni de vue personnalisée, style standard) et son chronomètre
  natif s'affiche dans la puce de la barre d'état. Réglage pour la désactiver ; lien vers le réglage
  système si l'utilisateur l'a refusée. HyperOS peut appliquer ses propres critères.
- Pendant une interruption, première action « Reprendre … » (Android n'affiche que 3 actions :
  Reprendre + 1 favorite + Arrêter).

## Notifications programmées (facultatives)

| Notification | Canal | Déclenchement | Une seule fois… |
|--------------|-------|---------------|-----------------|
| Session longue | Rappels | début + seuil (10 h) | par session |
| « Rien n'est suivi » (désactivé par défaut) | Rappels | fin de la dernière session (ou début de plage, ou dernière ouverture de l'app) + délai, dans la plage horaire | par période d'inactivité |
| Résumé du soir (désactivé par défaut) | Résumés (silencieux) | heure choisie, si quelque chose a été suivi | par jour |

Toutes reposent sur des alarmes **inexactes** reprogrammées par `refreshOutputs()` (redémarrage
compris) ; elles peuvent arriver quelques minutes en retard. Ouvrir l'app fait repartir la période
d'inactivité (l'utilisateur voit déjà que rien n'est suivi). Démarrer une activité retire le rappel
d'inactivité. Le résumé ouvre directement les bilans.

## Bilans enrichis

- **Trous** (`Gaps`) : périodes non suivies jusqu'à maintenant (jamais dans le futur) ; l'historique
  affiche celles d'au moins 5 min et ouvre une saisie préremplie (début arrondi à la minute
  supérieure, fin à la minute inférieure, pour rester dans la période libre).
- **Objectifs** : par jour (progression du jour, et « n jours sur les jours écoulés » en vue
  semaine) ou par semaine.
- **Comparaison** : semaine affichée contre la semaine précédente **arrêtée au même moment local**
  (pour une semaine en cours, comparer à une semaine complète serait trompeur).
- **Moyenne** : temps suivi ÷ jours écoulés de la semaine.
- **Séries** : jours consécutifs (jusqu'à aujourd'hui, ou hier si aujourd'hui n'est pas encore
  atteint) où l'activité est suivie au moins 1 min, ou atteint son objectif quotidien s'il existe.

## Interface

- Thème `ui/theme/Theme.kt` : palettes claire et sombre neutres, définies à la main ; pas de
  Material You (les couleurs du fond d'écran HyperOS ne modifient pas l'app) et pas de teinte
  d'élévation. Typographie : titres resserrés, grands chiffres en graisse fine, chiffres à chasse
  fixe (`tnum`) pour les durées.
- Composants `ui/components` : pastille d'activité, tuile (négatif = active), bouton pilule, barre
  de répartition (segments arrondis, gris = non suivi), sélecteur segmenté, en-têtes.
- Le chronomètre de l'écran principal s'adapte à la largeur (`TextAutoSize`) : il reste sur une
  ligne même avec une très grande taille de texte ; les raccourcis passent alors sur une colonne.
- La zone « en cours » a la même hauteur avec ou sans activité : les raccourcis ne se déplacent
  jamais sous le doigt.
- Aucune icône ni émoji n'est affiché (choix de sobriété) : une activité se reconnaît à son nom
  et à sa pastille de couleur. Le champ `icon` reste dans le modèle et les sauvegardes, inutilisé.

## Surfaces hors de l'application

Toutes lisent la base et passent par la même logique métier ; aucune n'a d'état propre.
`AppContainer.refreshOutputs()` les réaligne ensemble après chaque écriture (via `ChangeListener`),
au démarrage du processus, au redémarrage du téléphone, lors d'un changement d'heure ou de fuseau,
et juste après minuit.

| Surface | Contenu | Actions |
|---------|---------|---------|
| Notification de suivi | Activité, chronomètre natif, « En cours depuis » | Favorites, Arrêter, ouvrir |
| Widget « Suivi en cours » (`TrackingWidgetProvider`) | Activité, chronomètre natif, « Depuis… · aujourd'hui » (total animé), favorites | Arrêter, favorites, ouvrir |
| Widget « Activités » (`ActivitiesWidgetProvider`) | Toutes les activités (autant que la taille permet), l'active en négatif | Changer d'un appui, Arrêter |
| Tuile « Mon Temps » (`TrackingTileService`) | Activité en cours, ou « Reprendre … » | Arrêter / reprendre la dernière activité |
| Rappel de session longue | « Travail » toujours en cours ? | Continuer / Corriger (ouvre l'app) |

- **Widgets** : RemoteViews classiques, sans bibliothèque. `updatePeriodMillis = 0` : aucune mise à
  jour périodique. Les `Chronometer` sont animés par le lanceur ; leur base est en temps monotone
  (`elapsedRealtime`), recalculée au redémarrage et aux changements d'heure. La mise en page s'adapte
  à la taille réelle (ligne d'infos dès 110 dp, favorites dès 170 dp ; widget « Activités » : autant
  de rangées que la hauteur le permet, dans l'ordre des raccourcis). Couleurs et fonds sont des
  ressources : clair/sombre résolus par le lanceur.
- **Tuile** : liée uniquement par le système (`BIND_QUICK_SETTINGS_TILE`). Un appui arrête la
  session affichée (et seulement elle) ; sans suivi, reprend l'activité de la dernière session, ou
  ouvre l'app s'il n'y en a pas.
- **Actions** : PendingIntent explicites et immuables (`TrackingIntents`) vers `TrackingActionReceiver`,
  non exporté ; la source (`notification`, `widget`, `tile`) est enregistrée sur la session.
- **Alarmes** : uniquement inexactes (`AlarmManager.set`, `RTC`, sans réveil forcé ni permission
  « alarmes exactes ») : rafraîchissement juste après minuit (libellés « hier », total du jour) tant
  qu'un suivi ou un widget existe, et échéance du rappel. Elles peuvent être retardées de quelques
  minutes ; elles sont reprogrammées au redémarrage.
- **Rappel** : programmé au début de chaque session pour début + seuil ; annulé si la session
  change, s'arrête, ou si l'utilisateur répond « Continuer » (qui supprime aussi la question à la
  réouverture). Une seule notification par session. Si l'échéance est déjà passée (téléphone éteint),
  pas de rappel tardif : l'app pose la question à la réouverture.

## Sauvegarde

- **CSV** : `;` + BOM UTF-8 (Excel/LibreOffice en français), heures locales et UTC, durée en
  secondes et hh:mm:ss, note échappée.
- **JSON** : `format = "mon-temps-backup"`, `formatVersion = 3`, activités (dont interruption et
  objectif), sessions, préférences. Les fichiers v1 et v2 restent lisibles (valeurs par défaut ;
  Pause et Repas redeviennent des interruptions).
- **Restauration** : décodage et validation complète *avant* toute écriture (format, version,
  identifiants, références, dates, fuseaux, chevauchements, au plus une session active, pas de
  futur), puis confirmation, puis remplacement dans **une seule transaction**. Un fichier invalide
  ne modifie rien.
- Sélecteur de documents Android (SAF) : aucune permission de stockage. Sauvegarde cloud
  automatique Android désactivée (`allowBackup=false`) : l'utilisateur garde la maîtrise.
  Le transfert d'appareil à appareil (Android ou outil Xiaomi) est lui aussi exclu
  (`data_extraction_rules.xml`) : pour changer de téléphone, « Créer une sauvegarde » sur l'ancien
  puis « Restaurer une sauvegarde » sur le nouveau.
- **Sauvegarde automatique** (facultative) : un dossier choisi une fois (`OpenDocumentTree`,
  permission persistante limitée à ce dossier), une copie JSON complète tous les 7 jours
  (`mon-temps-auto-AAAA-MM-JJ.json`), les 8 plus récentes conservées. En cas d'échec (dossier
  supprimé, permission retirée), nouvel essai le lendemain et message dans les réglages. Le dossier
  et l'état de la sauvegarde automatique sont propres à l'appareil (non inclus dans le JSON).

## Préparation des futures applications (Coréen, Sport, Création)

Aucune interface inter-apps n'est exposée dans cette version. Ce qui est prêt :

- `TrackingCommands` (`currentSession`, `switchTo(activityId, source)`, `stop`) : interface métier
  stable ; `CommandSource.External(packageName)` est enregistré dans `sessions.source`.
- Identifiants d'activités stables et indépendants de la langue (`korean`, `sport`, `writing`, `music`).

Stratégie prévue (à implémenter quand la première app compagnon existera) :

1. Déclarer dans le manifeste une permission `fr.montemps.permission.CONTROL_TRACKING` avec
   `android:protectionLevel="signature"` : seules les apps signées avec la même clé (les miennes)
   l'obtiennent.
2. Exposer un composant protégé par cette permission, au choix :
   - un `BroadcastReceiver` exporté (`android:permission=…`) pour des commandes simples
     « passer à `korean` » / « arrêter » (fire-and-forget, pratique depuis une notification) ;
   - un service lié (AIDL ou `Messenger`) si une réponse est nécessaire (session en cours, résultat) ;
   - un `ContentProvider` en lecture seule (`readPermission` signature) pour consulter les sessions.
3. Le composant ne fait que traduire la requête en appel `TrackingCommands`, avec
   `CommandSource.External(callingPackage)` et validation de l'identifiant d'activité.
4. Pour « enregistrer un événement » (ex. quiz terminé), ajouter une table `events`
   (identifiant, type, instant, activité liée facultative, charge utile JSON) via une migration Room.

Ne pas partager la base Room entre apps : chaque app garde ses données privées et communique par
ces composants explicites. Ne jamais utiliser `sharedUserId` (déprécié).
