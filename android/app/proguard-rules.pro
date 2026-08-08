# JNI entry points are resolved by their exported native names.
-keep class com.ashcastle.duckyslicer.NativeEngine { *; }
-keep class com.u1.slicer.NativeLibrary { *; }
-keep class com.u1.slicer.data.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
