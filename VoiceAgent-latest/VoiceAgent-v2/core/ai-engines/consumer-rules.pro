# Передаются в :app автоматически (consumerProguardFiles).
# Moshi + KotlinJsonAdapterFactory работает через рефлексию — модели нельзя переименовывать/удалять.
-keepclassmembers class com.voiceagent.oneplus13.core.** {
    @com.squareup.moshi.Json <fields>;
}
-keep @com.squareup.moshi.JsonClass class com.voiceagent.oneplus13.core.** { *; }
-dontwarn org.vosk.**
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
