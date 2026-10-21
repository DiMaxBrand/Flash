# Keep NFC payload data classes
-keep class com.dimax.flash.nfc.** { *; }

# Ktor uses reflection for routing
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.dimax.flash.**$$serializer { *; }
-keepclassmembers class com.dimax.flash.** {
    *** Companion;
}
-keepclasseswithmembers class com.dimax.flash.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose Preview
-keep class androidx.compose.ui.tooling.preview.** { *; }

# Logback / SLF4J (logback-android finds its appenders and layouts by reflection)
-keep class ch.qos.logback.** { *; }
-dontwarn ch.qos.logback.**
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# Ktor server/client engines are looked up by service loader
-keep class kotlin.reflect.jvm.internal.** { *; }
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
