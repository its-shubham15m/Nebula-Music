# Basic ProGuard rules for Nebula Music App

# Keep Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Keep all model classes
-keep class com.shubhamgupta.nebula_music.models.** { *; }

# Keep Service and related classes
-keep class com.shubhamgupta.nebula_music.service.** { *; }

# Keep Fragment classes
-keep class com.shubhamgupta.nebula_music.fragments.** { *; }

# Keep Adapter classes
-keep class com.shubhamgupta.nebula_music.adapters.** { *; }

# Keep Repository and utility classes
-keep class com.shubhamgupta.nebula_music.repository.** { *; }
-keep class com.shubhamgupta.nebula_music.utils.** { *; }

# Keep BroadcastReceiver and related
-keepclassmembers class * extends android.content.BroadcastReceiver {
    public <init>();
    public void onReceive(android.content.Context, android.content.Intent);
}

# Keep Serializable and Parcelable classes
-keepnames class * implements java.io.Serializable
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep methods called via reflection
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AndroidX and Support library rules
-dontwarn android.support.**
-dontwarn androidx.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# Keep View bindings
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(***);
}

# Keep onClick methods
-keepclassmembers class * {
    public void *(android.view.View);
}

# Keep custom views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Keep GSON serializable classes
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# Material Components
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.internal.DispatchedContinuation {
    kotlinx.coroutines.CoroutineContext context;
}

# MediaSession
-keep class android.support.v4.media.session.** { *; }
-keep class androidx.media.session.** { *; }

# MediaPlayer and Audio
-keep class android.media.** { *; }