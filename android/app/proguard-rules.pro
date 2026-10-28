# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class ai.scan3d.app.** { *** Companion; }
-keepclasseswithmembers class ai.scan3d.app.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class ai.scan3d.app.**$$serializer { *; }
# JavaScript bridge used by the 3D viewer
-keepclassmembers class ai.scan3d.app.viewer.ViewerBridge { @android.webkit.JavascriptInterface <methods>; }
