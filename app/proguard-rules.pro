# kotlinx.serialization : conserver les sérialiseurs générés des classes de sauvegarde.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class fr.montemps.data.backup.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class fr.montemps.data.backup.**$$serializer { *; }
