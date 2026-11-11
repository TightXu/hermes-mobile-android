# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tightxu.hermesremote.**$$serializer { *; }
-keepclassmembers class com.tightxu.hermesremote.** {
    *** Companion;
}
-keepclasseswithmembers class com.tightxu.hermesremote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
