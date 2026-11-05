# kotlinx.serialization : conserver les sérialiseurs générés des classes de sauvegarde.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class fr.montemps.data.backup.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class fr.montemps.data.backup.**$$serializer { *; }

# SDK Connect IQ (montre Garmin) : ses objets (montre, app, messages) circulent entre Garmin
# Connect et Mon Temps sous leur nom de classe (extras d'Intent, sérialisation Monkey C) et le
# service lié est déclaré dans le manifeste : rien ne doit être renommé ni retiré.
-keep class com.garmin.android.connectiq.** { *; }
-keep class com.garmin.android.apps.connectmobile.connectiq.** { *; }
-keep class com.garmin.monkeybrains.serialization.** { *; }
-dontwarn com.garmin.**
