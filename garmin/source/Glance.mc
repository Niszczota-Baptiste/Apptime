import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

// Aperçu dans la liste des widgets : pastille + activité, puis durée et heure de début.
// Calculé depuis le dernier état enregistré (mis à jour par le service d'arrière-plan).
(:glance)
class MonTempsGlance extends WatchUi.GlanceView {
    private var timer as Timer.Timer? = null;

    function initialize() {
        GlanceView.initialize();
    }

    function onShow() as Void {
        var t = new Timer.Timer();
        t.start(method(:tick), 30000, true);
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
        var top = h * 30 / 100;
        var bottom = h * 70 / 100;
        var font = Graphics.FONT_GLANCE;
        var just = Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER;
        var s = Model.state();
        var a = Model.active(s);
        if (a == null) {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(0, top, font, "MON TEMPS", just);
            dc.setColor(Ui.MUTED, Graphics.COLOR_TRANSPARENT);
            dc.drawText(0, bottom, font, s == null ? "Ouvrir pour relier" : "Aucune activité", just);
            return;
        }
        var r = dc.getFontHeight(font) / 6;
        if (r < 4) {
            r = 4;
        }
        dc.setColor(Model.num(a, "c"), Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(r, top, r);
        var x = 4 * r;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, top, font, Ui.fit(dc, Model.str(a, "n"), font, w - x), just);
        dc.setColor(Ui.MUTED, Graphics.COLOR_TRANSPARENT);
        var line = Model.short(Model.elapsed(a)) + " · depuis " + Model.clock(Model.num(a, "s"));
        dc.drawText(0, bottom, font, Ui.fit(dc, line, font, w), just);
    }
}
