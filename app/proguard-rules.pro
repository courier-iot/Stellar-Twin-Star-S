# Keep all app code un-obfuscated (sync protocol, Room entities, widget, service)
-keep class com.stellar.twinstar.** { *; }

# OkHttp optional platform deps
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# zxing embedded scanner
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
