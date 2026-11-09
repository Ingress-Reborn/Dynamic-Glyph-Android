# Firebase's consumer rule keeps these manifest-discovered classes but omits
# the no-argument constructors used by ComponentDiscovery reflection.
-keepclassmembers class com.google.mlkit.common.internal.CommonComponentRegistrar {
    public <init>();
}
-keepclassmembers class com.google.mlkit.vision.text.internal.TextRegistrar {
    public <init>();
}
-keepclassmembers class com.google.mlkit.vision.common.internal.VisionCommonRegistrar {
    public <init>();
}
