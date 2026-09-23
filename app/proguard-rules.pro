-keep class androidx.media3.** { *; }
-dontwarn org.slf4j.**

# Named only in the manifest's meta-data, which R8 cannot see.
-keep class app.ember.tv.cast.CastOptionsProvider { *; }

# Keep stack traces in the in-app crash report readable.
-keepattributes SourceFile,LineNumberTable
