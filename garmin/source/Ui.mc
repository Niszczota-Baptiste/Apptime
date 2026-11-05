import Toybox.Graphics;
import Toybox.Lang;

// Petits outils de dessin, communs à l'app et à l'aperçu. Style du téléphone : texte sobre,
// une activité = une pastille de couleur et un nom (aucune icône).
(:glance)
module Ui {
    const MUTED = 0xAAAAAA;

    // Raccourcit [text] avec « … » pour qu'il tienne dans [maxWidth] pixels.
    function fit(dc as Graphics.Dc, text as String, font as Graphics.FontType, maxWidth as Number) as String {
        if (dc.getTextWidthInPixels(text, font) <= maxWidth) {
            return text;
        }
        var t = text;
        while (t.length() > 1 && dc.getTextWidthInPixels(t + "…", font) > maxWidth) {
            t = t.substring(0, t.length() - 1) as String;
        }
        return t + "…";
    }

    // Texte centré sur (x, y).
    function center(dc as Graphics.Dc, x as Number, y as Number, font as Graphics.FontType, text as String, color as Number) as Void {
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, y, font, fit(dc, text, font, dc.getWidth() * 84 / 100), Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // Pastille de couleur suivie du nom, l'ensemble centré sur [cx].
    function dotLabel(dc as Graphics.Dc, cx as Number, y as Number, font as Graphics.FontType, color as Number, text as String, maxWidth as Number) as Void {
        var r = dc.getFontHeight(font) / 7;
        if (r < 4) {
            r = 4;
        }
        var gap = r * 2;
        var label = fit(dc, text, font, maxWidth - 2 * r - gap);
        var total = 2 * r + gap + dc.getTextWidthInPixels(label, font);
        var x0 = cx - total / 2;
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(x0 + r, y, r);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x0 + 2 * r + gap, y, font, label, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // Plus grande police de la liste dans laquelle [text] tient en largeur.
    function largestFitting(dc as Graphics.Dc, text as String, fonts as Array<Graphics.FontType>, maxWidth as Number) as Graphics.FontType {
        for (var i = 0; i < fonts.size(); i++) {
            if (dc.getTextWidthInPixels(text, fonts[i]) <= maxWidth) {
                return fonts[i];
            }
        }
        return fonts[fonts.size() - 1];
    }
}
