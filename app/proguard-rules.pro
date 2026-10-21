# kotlinx.serialization keeps its generated serializers via its own consumer rules.
# Keep enum names stable for the library JSON format.
-keepclassmembers enum com.thecaticorn01.ircontroller.core.** { *; }
