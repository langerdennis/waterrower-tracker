# Keep Room database implementations & entities intact under R8
-keep class com.waterrower.tracker.data.WorkoutDatabase_Impl { *; }
-keep class com.waterrower.tracker.data.WorkoutDao_Impl { *; }
-keep class com.waterrower.tracker.data.WorkoutEntity { *; }
-keep class com.waterrower.tracker.data.WorkoutSampleEntity { *; }

# Keep Android components declared in AndroidManifest.xml
-keep class com.waterrower.tracker.WaterRowerApp { *; }
-keep class com.waterrower.tracker.MainActivity { *; }
-keep class com.waterrower.tracker.health.HealthConnectRationaleActivity { *; }
-keep class com.waterrower.tracker.service.RowingForegroundService { *; }

# Keep Health Connect IPC / AIDL / Protobuf models cleanly
-keep class androidx.health.connect.client.** { *; }
-keep class androidx.health.platform.client.** { *; }
-dontwarn androidx.health.connect.client.**
-dontwarn androidx.health.platform.client.**
