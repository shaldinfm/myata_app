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
# These began as conservative keeps for a release build that could not be
# verified, because nothing called Supabase yet. That gate has now been run.
#
# The reaction-sync phase drove the whole path - anonymous sign-in, a PostgREST
# upsert into reaction_events, the guarded update/insert into reactions, and the
# delete - through the real minified, release-signed APK on API 24 and API 36.
# No SerializationException, no NoClassDefFoundError, no VerifyError, and R8
# emitted no missing_rules.txt, meaning it asked for no keep rule this file does
# not already provide.
#
# **No new keep rule was needed for the sync phase.** Nothing was added here to
# make it pass, which is the result worth recording: the payloads are built as
# kotlinx.serialization JsonObject values rather than @Serializable classes, so
# the phase introduced no generated $$serializer for R8 to strip.
#
# Whether the rules below are still load-bearing is a separate question this did
# not answer - proving each unnecessary needs a release build and a device run per
# rule removed. Left alone deliberately rather than pruned on a guess.
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
