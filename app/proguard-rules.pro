# Pi Remote Control — R8 rules for release builds.
#
# KEEP: Custom data classes used in JSON (de)serialization. The hand-rolled
# JP parser accesses map keys by string — no reflection needed — but keeping
# these classes avoids confusion and protects against accidental renaming.
-keepclassmembers class com.piremote.** { *; }
-keep class com.piremote.** { *; }
-keep class com.piremote.db.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.coroutines — defensive; the library ships consumer rules but
# pinning these silences a stray warning on some R8 versions.
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
