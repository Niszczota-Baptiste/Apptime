import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// Liste des activités, dans l'ordre du téléphone : « Reprendre … » en tête (pendant une
// interruption ou sans suivi), puis chaque activité (pastille + nom), puis « Arrêter le suivi ».
module ActivityMenu {
    const RESUME = "@resume";
    const STOP = "@stop";

    function open(link as Link) as Void {
        var s = Model.state();
        if (s == null) {
            link.requestStatus();
            return;
        }
        var h = System.getDeviceSettings().screenHeight;
        var menu = new WatchUi.CustomMenu(h / 5, Graphics.COLOR_BLACK, {
            :focusItemHeight => h / 4,
            :title => new MenuTitle("Activité"),
        });
        var a = Model.active(s);
        var activeId = a != null ? Model.str(a, "id") : "";

        var resume = Model.dict(s, "r");
        if (resume == null && a == null) {
            resume = Model.dict(s, "l");
        }
        if (resume != null) {
            menu.addItem(new ActivityItem(RESUME, "Reprendre " + Model.str(resume, "n"), Model.num(resume, "c"), null));
        }

        var acts = s["acts"];
        if (acts instanceof Array) {
            for (var i = 0; i < acts.size(); i++) {
                var act = acts[i];
                if (act instanceof Dictionary) {
                    var id = Model.str(act, "id");
                    if (!id.equals("")) {
                        var current = id.equals(activeId);
                        menu.addItem(new ActivityItem(id, Model.str(act, "n"), Model.num(act, "c"), current ? "En cours" : null));
                    }
                }
            }
        }
        if (a != null) {
            menu.addItem(new ActivityItem(STOP, "Arrêter le suivi", -1, null));
        }
        WatchUi.pushView(menu, new ActivityMenuDelegate(link, activeId), WatchUi.SLIDE_UP);
    }
}

// Ligne de la liste : pastille de couleur (carré pour « Arrêter ») et nom ; l'élément ciblé
// est plus grand et en blanc.
class ActivityItem extends WatchUi.CustomMenuItem {
    private var label as String;
    private var color as Number;
    private var detail as String?;

    function initialize(id as String, text as String, c as Number, d as String?) {
        CustomMenuItem.initialize(id, {});
        label = text;
        color = c;
        detail = d;
    }

    function draw(dc as Graphics.Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var focused = isFocused();
        var font = focused ? Graphics.FONT_MEDIUM : Graphics.FONT_SMALL;
        var r = h / 10;
        if (r < 5) {
            r = 5;
        }
        var x = w * 18 / 100;
        var y = h / 2;
        if (color >= 0) {
            dc.setColor(color, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(x + r, y, r);
        } else {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.fillRectangle(x + r / 2, y - r / 2, r, r);
        }
        var textX = x + 2 * r + r * 2;
        var d = detail;
        var textY = d != null ? y - dc.getFontHeight(Graphics.FONT_XTINY) / 3 : y;
        dc.setColor(focused ? Graphics.COLOR_WHITE : Ui.MUTED, Graphics.COLOR_TRANSPARENT);
        dc.drawText(textX, textY, font, Ui.fit(dc, label, font, w - textX - w * 10 / 100), Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        if (d != null) {
            dc.setColor(Ui.MUTED, Graphics.COLOR_TRANSPARENT);
            dc.drawText(textX, textY + dc.getFontHeight(font) / 2 + 4, Graphics.FONT_XTINY, d, Graphics.TEXT_JUSTIFY_LEFT);
        }
    }
}

class MenuTitle extends WatchUi.Drawable {
    private var text as String;

    function initialize(t as String) {
        Drawable.initialize({});
        text = t;
    }

    function draw(dc as Graphics.Dc) as Void {
        dc.setColor(Ui.MUTED, Graphics.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2, Graphics.FONT_XTINY, text, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

class ActivityMenuDelegate extends WatchUi.Menu2InputDelegate {
    private var link as Link;
    private var activeId as String;

    function initialize(l as Link, current as String) {
        Menu2InputDelegate.initialize();
        link = l;
        activeId = current;
    }

    function onSelect(item as WatchUi.MenuItem) as Void {
        var id = item.getId();
        if (id instanceof String) {
            if (id.equals(ActivityMenu.STOP)) {
                // Arrêt : confirmation (un appui accidentel au poignet est vite arrivé).
                WatchUi.pushView(new WatchUi.Confirmation("Arrêter le suivi ?"), new StopConfirmation(link), WatchUi.SLIDE_IMMEDIATE);
                return;
            }
            if (id.equals(ActivityMenu.RESUME)) {
                link.command("resume", null);
            } else if (!id.equals(activeId)) {
                link.command("switch", id);
            }
        }
        WatchUi.popView(WatchUi.SLIDE_DOWN);
    }

    function onBack() as Void {
        WatchUi.popView(WatchUi.SLIDE_DOWN);
    }
}

class StopConfirmation extends WatchUi.ConfirmationDelegate {
    private var link as Link;

    function initialize(l as Link) {
        ConfirmationDelegate.initialize();
        link = l;
    }

    function onResponse(response as WatchUi.Confirm) as Boolean {
        if (response == WatchUi.CONFIRM_YES) {
            link.command("stop", null);
            // Fermer aussi la liste : retour direct à l'écran principal.
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        }
        return true;
    }
}
