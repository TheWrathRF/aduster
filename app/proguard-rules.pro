# JNI: native methods and the callback invoked from Rust by name.
-keepclasseswithmembernames class app.aduster.core.NativeCore {
    native <methods>;
}
-keep class app.aduster.core.NativeCore {
    public static int ownerUid(int, boolean, long, long, int, long, long, int);
    public static void onEngineExit();
}

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class app.aduster.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
