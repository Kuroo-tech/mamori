# Mamori R8 rules.
#
# The app performs no reflection on its own classes. The only reflective code is
# AutostartStatusReader, which looks up `checkOpNoThrow` on android.app.AppOpsManager —
# a platform class that R8 never renames or removes. No keep rule is required for it.
#
# AGP already keeps manifest components. They are listed here explicitly so that a future
# manifest/keep-rule mismatch fails loudly rather than producing a release that installs
# but cannot start its service or receiver.

-keep class com.reed.fcmguard.MainActivity { *; }
-keep class com.reed.fcmguard.GuardService { *; }
-keep class com.reed.fcmguard.BootReceiver { *; }

# Keep stack traces meaningful in crash reports; the mapping file alone is not shipped
# inside the APK, so line numbers are useful for logs shared from the diagnostics screen.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin metadata is not needed at runtime here, but keeping it costs almost nothing and
# avoids breaking future kotlin-reflect use in libraries.
-keepattributes *Annotation*, InnerClasses, Signature
