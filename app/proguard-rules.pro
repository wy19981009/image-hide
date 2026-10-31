# Add project specific ProGuard rules here.

# Hilt
-keep,allowobfuscation,allowshrinking class dagger.hilt.android.internal.lifecycle.HiltViewModelFactory
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.lifecycle.HiltViewModel class *

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Media3
-keep class androidx.media3.exoplayer.** { *; }

# IJK / GSY
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep class com.shuyu.gsyvideoplayer.** { *; }
-dontwarn tv.danmaku.ijk.media.player.**
-dontwarn com.shuyu.gsyvideoplayer.**

# Coil
-keep class coil.** { *; }

# Keep WorkManager Workers
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
