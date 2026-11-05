import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

module Main {
    // Ouverture de l'app : liaison avec le téléphone, puis écran principal.
    function open() as [WatchUi.Views, WatchUi.InputDelegates] {
        var link = new Link();
        link.start();
        return [new MainView(link), new MainDelegate(link)];
    }
}

// Écran principal : activité en cours, chronomètre, heure de début, total du jour.
class MainView extends WatchUi.View {
    private var link as Link;
    private var timer as Timer.Timer? = null;

    function initialize(l as Link) {
        View.initialize();
        link = l;
    }

    function onShow() as Void {
        var t = new Timer.Timer();
        t.start(method(:tick), 1000, true);
        timer = t;
    }

    function onHide() as Void {
        var t = timer;
        if (t != null) {
            t.stop();
        }
        timer = null;
    }

    function tick() as Void {
        WatchUi.requestUpdate();
    }

    function onUpdate(dc as Graphics.Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();

        var s = Model.state();
        var a = Model.active(s);
        if (s == null) {
            Ui.center(dc, cx, h * 40 / 100, Graphics.FONT_MEDIUM, "Mon Temps", Graphics.COLOR_WHITE);
            Ui.center(dc, cx, h * 54 / 100, Graphics.FONT_XTINY, "En attente du téléphone…", Ui.MUTED);
            Ui.center(dc, cx, h * 63 / 100, Graphics.FONT_XTINY, "Réglages → Montre Garmin", Ui.MUTED);
        } else if (a != null) {
            Ui.dotLabel(dc, cx, h * 26 / 100, Graphics.FONT_MEDIUM, Model.num(a, "c"), Model.str(a, "n"), w * 72 / 100);
            var text = Model.hms(Model.elapsed(a));
            var fonts = [Graphics.FONT_NUMBER_HOT, Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD] as Array<Graphics.FontType>;
            Ui.center(dc, cx, h * 46 / 100, Ui.largestFitting(dc, text, fonts, w * 84 / 100), text, Graphics.COLOR_WHITE);
            Ui.center(dc, cx, h * 63 / 100, Graphics.FONT_TINY, "depuis " + Model.clock(Model.num(a, "s")), Ui.MUTED);
        } else {
            Ui.center(dc, cx, h * 38 / 100, Graphics.FONT_MEDIUM, "Aucune activité", Graphics.COLOR_WHITE);
            var l = Model.dict(s, "l");
            if (l != null) {
                Ui.center(dc, cx, h * 52 / 100, Graphics.FONT_XTINY, "Reprendre " + Model.str(l, "n") + " : START", Ui.MUTED);
            } else {
                Ui.center(dc, cx, h * 52 / 100, Graphics.FONT_XTINY, "START : choisir une activité", Ui.MUTED);
            }
        }

        if (s != null) {
            var td = Model.today(s);
            if (td >= 0) {
                Ui.center(dc, cx, h * 75 / 100, Graphics.FONT_XTINY, "Aujourd'hui " + Model.short(td), Ui.MUTED);
            }
        }
        var status = link.statusLine();
        if (status != null) {
            Ui.center(dc, cx, h * 85 / 100, Graphics.FONT_XTINY, status, Graphics.COLOR_WHITE);
        }
    }
}

// START ou appui sur l'écran : liste des activités. Retour : quitter.
class MainDelegate extends WatchUi.BehaviorDelegate {
    private var link as Link;

    function initialize(l as Link) {
        BehaviorDelegate.initialize();
        link = l;
    }

    function onSelect() as Boolean {
        ActivityMenu.open(link);
        return true;
    }

    function onMenu() as Boolean {
        ActivityMenu.open(link);
        return true;
    }
}
