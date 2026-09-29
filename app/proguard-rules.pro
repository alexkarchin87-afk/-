-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keep class com.voiceagent.oneplus13.**.models.** { *; }
-dontwarn org.vosk.**
-keep class org.vosk.** { *; }
