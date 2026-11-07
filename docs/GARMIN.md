# Montre Garmin (Forerunner 965)

Trois volets, indépendants :

| Volet | Ce que ça fait | Ce qu'il faut |
|-------|----------------|---------------|
| **App montre « Mon Temps »** | Activité en cours, chronomètre, total du jour ; changer d'activité, reprendre, arrêter depuis le poignet, même sans téléphone à portée | L'app installée sur la montre (ci-dessous), Garmin Connect sur le téléphone, Réglages → Montre Garmin → **Relier la montre** |
| **Rappels au poignet** | Les rappels (session longue, « rien n'est suivi ») vibrent sur la montre | Garmin Connect → Notifications et alertes → Notifications des applications → **Mon Temps** |
| **Import des séances** | Les séances enregistrées par la montre deviennent des sessions (Sport par défaut), après votre choix | Garmin Connect ≥ 5.14 synchronisé avec Health Connect ; Réglages → Montre Garmin → **Importer les séances sportives** |

La notification de suivi permanente est marquée « réservée au téléphone » (`setLocalOnly`) : la
montre a son propre affichage, et une notification mise à jour à chaque changement ferait vibrer
le poignet inutilement (si Garmin Connect respecte ce marquage).

## 1. Installer l'app sur la montre

Le code est dans `garmin/` (Monkey C, Connect IQ, minimum API 3.2 ; cible déclarée : `fr965`).

**Option rapide — fichier fourni.** `MonTemps-fr965.prg` (compilé dans le conteneur de
développement, voir « Vérification » plus bas) :

1. Brancher la montre à un ordinateur en USB. Windows l'affiche dans l'Explorateur (appareil
   « Forerunner 965 ») ; sur Mac, utiliser OpenMTP (Garmin Express fermé).
2. Copier le fichier dans `GARMIN/APPS/`, débrancher.
3. L'app apparaît dans la liste des applications de la montre (bouton START depuis le cadran →
   faire défiler). Pour l'aperçu : maintenir UP → Aperçus → Ajouter → Mon Temps.

Des développeurs signalent que, sur certaines montres récentes (stockage MTP), le fichier copié
n'apparaît pas : utiliser alors l'option officielle ci-dessous.

**Option officielle — compiler soi-même (compte développeur Garmin gratuit).**

1. Installer Visual Studio Code, l'extension « Monkey C » et le Connect IQ SDK Manager ; s'y
   connecter, télécharger le dernier SDK et l'appareil « Forerunner 965 ».
2. Palette de VS Code : « Monkey C: Generate a Developer Key » (une seule fois ; à conserver).
3. Ouvrir le dossier `garmin/`, puis « Monkey C: Build for Device » → `fr965` ; ou en ligne de
   commande :
   ```bash
   monkeyc -f garmin/monkey.jungle -d fr965 -o MonTemps.prg -y developer_key.der -l 3
   ```
4. Installer comme ci-dessus (USB), ou en **bêta privée** : « Monkey C: Export Project » (fichier
   `.iq`), le déposer sur le site Connect IQ développeur en cochant « Beta App », puis l'installer
   depuis la boutique web avec le même compte que la montre. Une bêta n'est visible que par ce
   compte.

L'identifiant de l'app (`manifest.xml`) doit rester `b01f1cbd-213b-4906-be10-3dd0691bb529` : c'est
lui que le téléphone reconnaît (`WatchProtocol.APP_ID`). Un fichier signé avec une autre clé que
celle d'une installation précédente peut demander de désinstaller d'abord l'app de la montre
(seuls l'état mis en cache et la file d'attente y sont stockés).

## 2. Sur la montre

- **Aperçu** (liste des widgets) : pastille de couleur + activité, durée et heure de début. Mis à
  jour quand le téléphone envoie un nouvel état, même app fermée (service d'arrière-plan).
- **Écran principal** : activité, chronomètre (calculé sur la montre à partir de l'heure de début :
  aucun message chaque seconde), « depuis 14:05 », total du jour, ligne d'état (envoi, hors ligne,
  refus du téléphone).
- **START ou appui sur l'écran** : liste des activités dans l'ordre du téléphone, « Reprendre … »
  en tête (pendant une interruption, ou sans suivi), « Arrêter le suivi » en bas (avec
  confirmation). Une brève vibration confirme que le téléphone a reçu la commande.
- **Sous-catégories** : une activité qui en a (Travail, Pause, Sport, Écriture, Musique,
  Déplacement…) ouvre un second menu : Perso / Pro…, la sous-catégorie en cours marquée
  « En cours », et « Sans précision » si l'activité n'est pas déjà en cours. L'écran principal
  affiche le nom complet (« Travail · Pro ») ; « Reprendre » reprend aussi la sous-catégorie.
  La liste vient du téléphone : une sous-catégorie ajoutée, renommée ou retirée dans l'app y
  apparaît au prochain envoi d'état.
- **Sans téléphone** (sortie sans téléphone, Bluetooth coupé) : la montre affiche tout de suite le
  changement et garde la commande en file avec son heure. Au retour de la connexion, le téléphone
  l'applique à cette heure si c'est encore possible (« j'ai commencé Sport à 18:00 »). Si quelque
  chose de plus récent a été fait sur le téléphone, la commande est refusée et la montre affiche
  « Ignorée : changement plus récent sur le téléphone » : **le téléphone fait foi**. Une commande
  de plus de deux jours est ignorée.

## 3. Sur le téléphone

Réglages → **Montre Garmin** :

- **Relier la montre** : active la liaison (désactivée par défaut). Sous l'interrupteur, l'état :
  Garmin Connect absent, aucune montre associée, montre non connectée, app non installée sur la
  montre, connectée.
- **Envoyer l'état à la montre** : renvoie l'état courant (normalement automatique : à chaque
  changement, à l'ouverture de l'app montre, à la reconnexion de la montre).
- **Ouvrir Garmin Connect**, **Importer les séances sportives**.

### Import des séances (Health Connect)

Lecture seule des séances d'exercice des 30 derniers jours, uniquement quand cet écran est ouvert.
Chaque séance est proposée avec son type, sa durée, ses heures et son app d'origine :

- cochée d'office si le créneau est libre ;
- « Déjà dans l'historique » si une session occupe le même créneau (à une minute près) ;
- « Chevauche « Travail » » si elle recouvre une session existante (ou en cours) : jamais
  d'écrasement ; corriger d'abord l'historique si besoin ;
- la même séance copiée par deux apps n'est proposée qu'une fois.

« Ajouter comme » : Sport par défaut, ou toute autre activité. La note de la session indique le
type et l'origine (« Course à pied · Sortie longue (Garmin Connect) »). Rien n'est écrit dans
Health Connect.

Si la liste reste vide : vérifier dans Garmin Connect que le partage avec Health Connect est
activé, et dans Health Connect (Paramètres du téléphone) que des séances « Exercice » venant de
Garmin Connect existent. Les sources consultées ne s'accordent pas sur la copie des séances par
Garmin : à vérifier sur le téléphone.

## 4. Protocole (pour la maintenance)

Messages Connect IQ (dictionnaires Monkey C ⇄ `Map` Java), clés courtes. Heures en secondes Unix
(entiers 32 bits : valables jusqu'en 2038), couleurs en `0xRRGGBB`.

Montre → téléphone :

| Message | Effet |
|---------|-------|
| `{"v":1, "c":"status"}` | Renvoyer l'état (ouverture de l'app montre) |
| `{"v":1, "c":"switch", "id":"sport", "at":1762362000}` | Démarrer / changer d'activité (à l'heure `at` si elle date de plus de 30 s) |
| `{"v":1, "c":"switch", "id":"work", "sub":"work_pro", "at":…}` | Idem avec une sous-catégorie ; même activité sans sous-catégorie : la session est seulement précisée (même début). Sous-catégorie retirée entre-temps : refus « Sous-catégorie indisponible » |
| `{"v":1, "c":"stop", "at":…}` | Arrêter le suivi |
| `{"v":1, "c":"resume", "at":…}` | Reprendre l'activité interrompue (ou la dernière, sans suivi) |

Téléphone → montre (après chaque changement, réponse à toute commande) :

```
{"v":1, "t":"state", "now":…, "d":20261105,
 "a":{"id":"work","n":"Travail · Pro","c":0x4A78E0,"s":1762326000,"i":false,
      "sub":"work_pro" | null} | null,   // "n" : nom affiché, sous-catégorie comprise
 "td":10800,                       // temps suivi aujourd'hui à "now" (s)
 "r":{"id","n","c","sub"} | null, // « Reprendre » pendant une interruption ("n" complet)
 "l":{"id","n","c","sub"} | null, // dernière activité, sans suivi ("n" complet)
 "acts":[{"id","n","c"},           // activités ; "subs" seulement si elle en a :
         {"id","n","c","subs":[{"id":"work_perso","n":"Perso"}, …]}, …],
 "fav":["break","work"],
 "msg":"…" | null}                 // refus éventuel de la dernière commande
```

Côté Android : `garmin/WatchProtocol.kt` (format, testé), `garmin/WatchController.kt` (activation,
envoi seulement si l'état change), `garmin/ConnectIqLink.kt` (SDK), `domain/watch/WatchCommands.kt`
(exécution via `TimeTracker`, source `watch`). Les commandes passent par `TrackingIntents` →
`TrackingActionReceiver`, comme celles de la notification.

## 5. Sécurité et permissions

- SDK Connect IQ pour Android (`com.garmin.connectiq:ciq-companion-app-sdk:2.4.0`) : aucune
  permission ajoutée. Il déclare un service exporté (`IQGarminBindingService`) auquel Garmin
  Connect se lie ; le SDK vérifie la signature de l'appelant (Garmin) avant de transmettre quoi que
  ce soit. Ce service est **désactivé** dans le manifeste tant que la liaison n'est pas activée.
- Seule cette voie vérifiée est écoutée : aucun écouteur « par montre » n'est enregistré, si bien
  que les diffusions de messages (que n'importe quelle app pourrait imiter) restent sans effet.
- Health Connect : seule `android.permission.health.READ_EXERCISE` (lecture des séances), demandée
  depuis l'écran d'import ; écran d'explication fourni à Android (`HealthPermissionRationaleActivity`).
- App montre : permissions `Communications` (échanges avec le téléphone) et `Background` (mise à
  jour de l'aperçu). Aucune donnée ne quitte le téléphone et la montre.

## 6. Vérification et limites

Vérifié dans le conteneur de développement (sans montre ni téléphone) :

- tests Android (protocole, sous-catégories dans les deux sens, commandes de la montre jusqu'au
  moteur de suivi, file hors ligne et refus, activation et service désactivé par défaut, vrai SDK
  Garmin sans Garmin Connect, import des séances) ;
- l'app montre **compile sans erreur au niveau de vérification de types le plus strict** (`-l 3`)
  avec le compilateur officiel Connect IQ 9.3.0, pour la Forerunner 965. Le fichier de définition
  officiel de la montre exige un compte Garmin : la compilation a utilisé une définition
  reconstituée à partir des données de la montre fournies avec le SDK (`devices.xml` : écran
  454 × 454, API 5.2, mémoire par type d'app). Compiler soi-même avec la définition officielle
  reste la voie sûre.

Non vérifié (aucun matériel) : affichage réel sur la montre, lancement de l'app, délais de la
liaison Bluetooth, réception en arrière-plan (des développeurs la signalent capricieuse sur
certaines montres : l'app redemande de toute façon l'état à l'ouverture), copie des séances par
Garmin Connect dans Health Connect, prise en compte de `setLocalOnly` par Garmin Connect.
→ Procédure : `docs/TEST_TELEPHONE.md`, section 22.
