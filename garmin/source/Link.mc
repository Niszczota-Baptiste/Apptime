import Toybox.Attention;
import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.WatchUi;

// Liaison avec l'app Android, par l'intermédiaire de Garmin Connect.
//
// Les commandes passent par une file conservée sur la montre : sans téléphone (sortie sans
// téléphone, Bluetooth coupé), elles partent au retour de la connexion avec leur heure
// d'origine, et le téléphone les applique à cette heure si c'est encore possible.
class Link {
    var sending as Boolean = false;
    var offline as Boolean = false;
    var message as String? = null;
    var messageAt as Number = 0;

    function initialize() {
    }

    function start() as Void {
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        // Aperçu à jour même app fermée : le service d'arrière-plan enregistre chaque état reçu.
        if (Background has :registerForPhoneAppMessageEvent) {
            Background.registerForPhoneAppMessageEvent();
        }
        requestStatus();
        flush();
    }

    function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (data instanceof Dictionary && Model.isState(data)) {
            Model.saveState(data);
            offline = false;
            var m = data["msg"];
            if (m instanceof String) {
                message = m;
                messageAt = Model.now();
            }
            WatchUi.requestUpdate();
            flush();
        }
    }

    // Demande l'état courant (ouverture de l'app) ; n'est pas mise en file.
    function requestStatus() as Void {
        Communications.transmit({"v" => 1, "c" => "status"}, null, new StatusListener(self));
    }

    // Commande de l'utilisateur : affichée tout de suite, mise en file, envoyée dès que possible.
    function command(cmd as String, id as String?) as Void {
        Model.applyLocally(cmd, id);
        var c = {"v" => 1, "c" => cmd, "at" => Model.now()} as Dictionary;
        if (id != null) {
            c["id"] = id;
        }
        var q = Model.queue();
        q.add(c);
        if (q.size() > Model.MAX_QUEUE) {
            q = q.slice(q.size() - Model.MAX_QUEUE, null);
        }
        Model.setQueue(q);
        WatchUi.requestUpdate();
        flush();
    }

    // Envoie la plus ancienne commande en attente ; la suivante part quand elle est remise.
    function flush() as Void {
        if (sending) {
            return;
        }
        var q = Model.queue();
        if (q.size() == 0) {
            return;
        }
        sending = true;
        var next = q[0] as Dictionary<Communications.TransmitKeyType, Communications.TransmitType>;
        Communications.transmit(next, null, new SendListener(self));
    }

    function onSent() as Void {
        var q = Model.queue();
        if (q.size() > 0) {
            Model.setQueue(q.slice(1, null));
        }
        sending = false;
        offline = false;
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(40, 120)]);
        }
        flush();
        WatchUi.requestUpdate();
    }

    function onFailed() as Void {
        sending = false;
        offline = true;
        WatchUi.requestUpdate();
    }

    // Ligne d'état en bas de l'écran, ou null si tout est à jour.
    function statusLine() as String? {
        var m = message;
        if (m != null && Model.now() - messageAt < 15) {
            return m;
        }
        var pending = Model.queue().size();
        if (pending > 0) {
            return offline ? "Hors ligne · " + pending + " en attente" : "Envoi…";
        }
        if (offline) {
            return "Téléphone injoignable";
        }
        return null;
    }
}

class SendListener extends Communications.ConnectionListener {
    private var link as Link;

    function initialize(l as Link) {
        ConnectionListener.initialize();
        link = l;
    }

    function onComplete() as Void {
        link.onSent();
    }

    function onError() as Void {
        link.onFailed();
    }
}

class StatusListener extends Communications.ConnectionListener {
    private var link as Link;

    function initialize(l as Link) {
        ConnectionListener.initialize();
        link = l;
    }

    function onComplete() as Void {
        link.offline = false;
        WatchUi.requestUpdate();
    }

    function onError() as Void {
        link.offline = true;
        WatchUi.requestUpdate();
    }
}
