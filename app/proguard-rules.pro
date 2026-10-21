# kotlinx.serialization keeps its generated serializers via its own consumer rules.
# Keep enum names stable for the library JSON format.
-keepclassmembers enum com.thecaticorn01.ircontroller.core.** { *; }

# WorkManager (pulled in by Glance for the home-screen widget) initialises at app
# start and creates its Room database by name at runtime (Class.forName of the
# generated WorkDatabase_Impl). Without this, R8 renames or removes that class and
# release builds crash at launch with "Failed to create an instance of
# androidx.work.impl.WorkDatabase".
-keep class * extends androidx.room.RoomDatabase { <init>(); }
