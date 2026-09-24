# Add project specific ProGuard rules here.

# kotlinx.serialization: keep serializer() generated code discoverable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class cc.gilmore.todoai.data.remote.dto.** {
    *** Companion;
}
-keepclasseswithmembers class cc.gilmore.todoai.data.remote.dto.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# AWS Cognito SDK does some reflection internally.
-keep class com.amazonaws.** { *; }
-dontwarn com.amazonaws.**
