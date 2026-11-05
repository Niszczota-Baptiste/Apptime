import Toybox.Application;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// Point d'entrée. L'application est chargée dans trois contextes : l'app elle-même, l'aperçu
// (glance) et le service d'arrière-plan qui enregistre l'état envoyé par le téléphone.
(:glance, :background)
class MonTempsApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
    }

    // Chacune des trois méthodes suivantes n'est appelée que dans son propre contexte : la
    // vérification de portée correspondante est désactivée (annotation prévue par Garmin).
    (:typecheck([disableBackgroundCheck, disableGlanceCheck]))
    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        return Main.open();
    }

    (:typecheck(disableBackgroundCheck))
    function getGlanceView() as [WatchUi.GlanceView] or [WatchUi.GlanceView, WatchUi.GlanceViewDelegate] or Null {
        return [new MonTempsGlance()];
    }

    (:typecheck(disableGlanceCheck))
    function getServiceDelegate() as [System.ServiceDelegate] {
        return [new PhoneService()];
    }

    // Le service d'arrière-plan a déjà enregistré l'état : il suffit de redessiner.
    function onBackgroundData(data as Application.PersistableType) as Void {
        WatchUi.requestUpdate();
    }
}
