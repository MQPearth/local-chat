# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.localchat.android.**$$serializer { *; }
-keepclassmembers class com.localchat.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.localchat.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}
