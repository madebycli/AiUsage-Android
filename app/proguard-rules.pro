# kotlinx.serialization: keep generated serializers of the stored and parsed models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.madebycli.aiusage.** {
    *** Companion;
}
-keepclasseswithmembers class com.madebycli.aiusage.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.madebycli.aiusage.**$$serializer { *; }

# OkHttp optional TLS providers are not on the classpath.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
