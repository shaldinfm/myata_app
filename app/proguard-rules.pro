# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep ViewModel classes
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# Keep DataBinding classes
-keep class androidx.databinding.** { *; }
-keep class * extends androidx.databinding.ViewDataBinding { *; }
-keep class com.example.musicplayerapp.databinding.** { *; }

# Keep GSON models if used via reflection (though we removed Retrofit, Gson might still be used manually)
-keep class com.example.musicplayerapp.** { *; }
# Supabase / Ktor / kotlinx-serialization.
#
# These are conservative keeps for a release build this branch cannot verify:
# release variants are owner-only here (docs/RELEASE_SIGNING.md), so nobody has
# yet run R8 over this dependency set. kotlinx-serialization generates companion
# serializers that are reached reflectively, and R8 strips them silently - the
# symptom is a SerializationException at runtime, in release only.
#
# Before the reaction-sync phase ships, a release build has to be made and the
# anonymous sign-in exercised on it; if these prove unnecessary, delete them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.jan.supabase.**$$serializer { *; }
-keepclassmembers class io.github.jan.supabase.** {
    *** Companion;
}
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
