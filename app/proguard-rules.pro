# ----------------------------------------------------------------------------
# NEBULA PLAYER PROGUARD RULES
# ----------------------------------------------------------------------------

# Keep all data models (prevent obfuscation of fields used in JSON/Serialization)
-keep class com.shubhamgupta.nebula_player.models.** { *; }

# Keep Service classes (MusicService etc.)
-keep class com.shubhamgupta.nebula_player.service.** { *; }

# Keep Fragment classes (Reflected by Navigation or FragmentManager)
-keep class com.shubhamgupta.nebula_player.fragments.** { *; }

# Keep Adapter classes
-keep class com.shubhamgupta.nebula_player.adapters.** { *; }

# Keep Repository and utility classes
-keep class com.shubhamgupta.nebula_player.repository.** { *; }
-keep class com.shubhamgupta.nebula_player.utils.** { *; }

# Keep Custom Views
-keep public class com.shubhamgupta.nebula_player.views.** { *; }

# ----------------------------------------------------------------------------
# LIBRARY SPECIFIC RULES
# ----------------------------------------------------------------------------

# --- GLIDE ---
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# --- MEDIA3 / EXOPLAYER (Important for Video Player) ---
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }

# --- RETROFIT & OKHTTP ---
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
# Keep generic type signatures for Retrofit calls
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# --- GOOGLE GENERATIVE AI (GEMINI) ---
-keep class com.google.ai.client.generativeai.** { *; }

# --- JSOUP (Web Scraping) ---
-keep class org.jsoup.** { *; }

# --- GSON ---
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# --- KOTLIN COROUTINES ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.internal.DispatchedContinuation {
    kotlinx.coroutines.CoroutineContext context;
}

# --- ANDROIDX & MATERIAL ---
-dontwarn android.support.**
-dontwarn androidx.**
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# ----------------------------------------------------------------------------
# STANDARD ANDROID RULES
# ----------------------------------------------------------------------------

# Keep BroadcastReceiver
-keepclassmembers class * extends android.content.BroadcastReceiver {
    public <init>();
    public void onReceive(android.content.Context, android.content.Intent);
}

# Keep Serializable and Parcelable
-keepnames class * implements java.io.Serializable
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Keep Enum values (needed for valueOf() reflection)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep View Constructors (for XML inflation)
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(***);
}

# Keep onClick methods defined in XML
-keepclassmembers class * {
    public void *(android.view.View);
}

# Keep Attributes
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod
-keepattributes InnerClasses