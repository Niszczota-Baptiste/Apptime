import Toybox.Application;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Time.Gregorian;

// État reçu du téléphone, file des commandes hors ligne et mise en forme. Partagé par l'app,
// l'aperçu (glance) et le service d'arrière-plan. Format des messages : docs/GARMIN.md.
(:glance, :background)
module Model {
    const KEY_STATE = "state";
    const KEY_QUEUE = "queue";
    const MAX_QUEUE = 30;

    function now() as Number {
        return Time.now().value();
    }

    // Dernier état connu, ou null si le téléphone n'a encore rien envoyé.
    function state() as Dictionary? {
        var s = Application.Storage.getValue(KEY_STATE);
        if (s instanceof Dictionary) {
            return s as Dictionary;
        }
        return null;
    }

    // Message « état » du téléphone (les autres types sont ignorés).
    function isState(data as Object?) as Boolean {
        if (data instanceof Dictionary) {
            var t = data["t"];
            if (t instanceof String) {
                return t.equals("state");
            }
        }
        return false;
    }

    function saveState(s as Dictionary) as Void {
        Application.Storage.setValue(KEY_STATE, s as Application.Storage.ValueType);
    }

    // Activité en cours {"id", "n", "c", "s", "i"} ou null.
    function active(s as Dictionary?) as Dictionary? {
        return dict(s, "a");
    }

    function dict(d as Dictionary?, key as String) as Dictionary? {
        if (d == null) {
            return null;
        }
        var v = d[key];
        if (v instanceof Dictionary) {
            return v;
        }
        return null;
    }

    function num(d as Dictionary?, key as String) as Number {
        if (d == null) {
            return 0;
        }
        var v = d[key];
        if (v instanceof Number) {
            return v;
        }
        if (v instanceof Long || v instanceof Float || v instanceof Double) {
            return v.toNumber();
        }
        return 0;
    }

    function str(d as Dictionary?, key as String) as String {
        if (d == null) {
            return "";
        }
        var v = d[key];
        if (v instanceof String) {
            return v;
        }
        return "";
    }

    // Jour local de la montre, AAAAMMJJ (même codage que le champ "d" du téléphone).
    function dayKey() as Number {
        var info = Gregorian.info(Time.now(), Time.FORMAT_SHORT);
        return (info.year as Number) * 10000 + (info.month as Number) * 100 + (info.day as Number);
    }

    // Temps suivi aujourd'hui en secondes, ou -1 si l'état date d'un autre jour.
    function today(s as Dictionary?) as Number {
        if (s == null || num(s, "d") != dayKey()) {
            return -1;
        }
        var td = num(s, "td");
        if (active(s) != null) {
            td += now() - num(s, "now");
        }
        return td;
    }

    // Durée de l'activité en cours : calculée sur la montre à partir du début (aucun message
    // n'est nécessaire chaque seconde).
    function elapsed(a as Dictionary) as Number {
        var e = now() - num(a, "s");
        return e < 0 ? 0 : e;
    }

    // « 1:02:03 »
    function hms(sec as Number) as String {
        return (sec / 3600).format("%d") + ":" + (sec % 3600 / 60).format("%02d") + ":" + (sec % 60).format("%02d");
    }

    // « 1 h 05 », « 45 min »
    function short(sec as Number) as String {
        var h = sec / 3600;
        var m = sec % 3600 / 60;
        if (h == 0) {
            return m.format("%d") + " min";
        }
        return h.format("%d") + " h " + m.format("%02d");
    }

    // « 14:05 », heure locale de la montre.
    function clock(epoch as Number) as String {
        var info = Gregorian.info(new Time.Moment(epoch), Time.FORMAT_SHORT);
        return (info.hour as Number).format("%02d") + ":" + (info.min as Number).format("%02d");
    }

    // File des commandes pas encore remises au téléphone (plus ancienne en premier).
    function queue() as Array<Dictionary> {
        var q = Application.Storage.getValue(KEY_QUEUE);
        if (q instanceof Array) {
            return q as Array<Dictionary>;
        }
        return [] as Array<Dictionary>;
    }

    function setQueue(q as Array<Dictionary>) as Void {
        Application.Storage.setValue(KEY_QUEUE, q as Application.Storage.ValueType);
    }

    function findActivity(s as Dictionary, id as String) as Dictionary? {
        var acts = s["acts"];
        if (acts instanceof Array) {
            for (var i = 0; i < acts.size(); i++) {
                var a = acts[i];
                if (a instanceof Dictionary) {
                    var aid = a["id"];
                    if (aid instanceof String && aid.equals(id)) {
                        return a;
                    }
                }
            }
        }
        return null;
    }

    // Sous-catégories proposées pour une activité de la liste (« subs »), ou tableau vide.
    function subsOf(act as Dictionary?) as Array {
        if (act != null) {
            var subs = act["subs"];
            if (subs instanceof Array) {
                return subs;
            }
        }
        return [] as Array;
    }

    function subName(act as Dictionary?, subId as String?) as String? {
        if (subId == null) {
            return null;
        }
        var subs = subsOf(act);
        for (var i = 0; i < subs.size(); i++) {
            var sub = subs[i];
            if (sub instanceof Dictionary) {
                var sid = sub["id"];
                if (sid instanceof String && sid.equals(subId)) {
                    return str(sub, "n");
                }
            }
        }
        return null;
    }

    // Applique tout de suite une commande à l'état affiché ; le téléphone confirmera (ou
    // corrigera) en renvoyant son état, qui fait foi.
    function applyLocally(cmd as String, id as String?, sub as String?) as Void {
        var s = state();
        if (s == null) {
            return;
        }
        var t = now();
        var td = today(s);
        var current = active(s);
        var target = null as Dictionary?;
        if (cmd.equals("resume")) {
            target = dict(s, "r");
            if (target == null) {
                target = dict(s, "l");
            }
        } else if (cmd.equals("switch") && id != null) {
            target = findActivity(s, id);
        }
        if (cmd.equals("stop")) {
            if (current == null) {
                return;
            }
            s["l"] = {"id" => current["id"], "n" => current["n"], "c" => current["c"], "sub" => current["sub"]};
            s["a"] = null;
        } else if (target != null) {
            var start = t;
            if (current != null && str(current, "id").equals(str(target, "id"))) {
                // Même activité : rien sans sous-catégorie nouvelle ; une session sans
                // sous-catégorie est seulement précisée (même début).
                var currentSub = current["sub"];
                if (sub == null || (currentSub instanceof String && currentSub.equals(sub))) {
                    return;
                }
                if (currentSub == null) {
                    start = num(current, "s");
                }
            }
            var label = str(target, "n");
            var newSub = sub;
            if (cmd.equals("resume")) {
                // « Reprendre » : le téléphone a déjà mis le nom complet et la sous-catégorie.
                var rs = target["sub"];
                newSub = rs instanceof String ? rs : null;
            } else {
                var subLabel = subName(target, sub);
                if (subLabel != null) {
                    label = label + " · " + subLabel;
                }
            }
            s["a"] = {"id" => target["id"], "n" => label, "c" => target["c"], "s" => start, "i" => false, "sub" => newSub};
            s["l"] = null;
        } else {
            return;
        }
        s["r"] = null;
        if (td >= 0) {
            s["td"] = td;
        }
        s["now"] = t;
        saveState(s);
    }
}
