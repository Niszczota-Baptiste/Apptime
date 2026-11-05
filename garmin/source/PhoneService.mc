import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;

// Arrière-plan : le téléphone a envoyé un nouvel état alors que l'app était fermée. On
// l'enregistre pour que l'aperçu et la prochaine ouverture soient à jour.
(:background)
class PhoneService extends System.ServiceDelegate {
    function initialize() {
        ServiceDelegate.initialize();
    }

    function onPhoneAppMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (data instanceof Dictionary && Model.isState(data)) {
            Model.saveState(data);
        }
        Background.exit(null);
    }
}
