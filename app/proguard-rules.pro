# kotlinx.serialization: i modelli @Serializable dell'app e del core.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.pampa.pampanotes.**$$serializer { *; }
-keepclassmembers class dev.pampa.pampanotes.** { *** Companion; }
-keepclasseswithmembers class dev.pampa.pampanotes.** { kotlinx.serialization.KSerializer serializer(...); }

# pdfbox-android usa la riflessione sui font e sui filtri.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.commons.logging.**
