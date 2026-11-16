# R8 keep rules for Riplox TT.
#
# Why R8 at all: Play flagged the bundle "DEX code optimization below threshold: Obfuscation 0%"
# (deadline Feb 2027). Libraries that ship their own consumer rules (AdMob, UMP, WorkManager,
# Compose, Coil, Play app-update, coroutines) are covered by those; what follows is only what
# they do NOT cover.

# --- youtubedl-android (yt-dlp engine + ffmpeg) -------------------------------------------
# It maps yt-dlp's JSON onto its own classes with Jackson (reflection on fields and getters),
# and unpacks the Python/ffmpeg packages with commons-compress. Renaming or stripping any of
# that breaks getInfo() / the engine at runtime, not at build time — so keep it whole.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.io.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn java.beans.**

# --- WorkManager's Room database ------------------------------------------------------------
# Room builds WorkDatabase_Impl by NAME (reflection). With R8 on, the generated class was
# stripped and the app crashed at launch ("Failed to create an instance of class
# androidx.work.impl.WorkDatabase") — measured on the QA phone, 16-Nov-2026.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
# Same story, next step: every job sat at "Queued" forever because WorkManager could not build
# its input merger ("NoSuchMethodException: OverwritingInputMerger.<init>") — also by name.
# Workers, mergers and startup initializers are all created from a class name.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * implements androidx.startup.Initializer { <init>(); }

# --- Crash reports stay readable ------------------------------------------------------------
# Line numbers survive (Play deobfuscates with the mapping file uploaded alongside the bundle).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
