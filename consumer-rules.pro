# Consumer R8/ProGuard rules for Humla.
#
# The AAR itself is intentionally not minified. These rules are packaged with
# the AAR and applied by the consuming Android app (SSVR) during its final R8
# pass, allowing whole-program shrinking while preserving JNI boundaries.

# JavaCPP 0.7 native libraries resolve these runtime classes, fields, and
# methods by their exact JNI names (for example Pointer.address and
# Loader.putMemberOffset). Keep this small legacy runtime intact.
-keep class com.googlecode.javacpp.** { *; }

# JavaCPP 0.7 also bundles BuildMojo in the same artifact. Its Maven plugin API
# references are build-time only and are intentionally absent from Android.
# They are unreachable in Humla/SSVR, so do not make them R8 missing-class
# errors in consuming applications.
-dontwarn org.apache.maven.plugin.**

# Humla's generated Opus/Speex JNI entry points encode the Java class and
# native method names. Preserve names only for wrapper classes that actually
# contain native methods; ordinary Humla code remains shrinkable/obfuscatable.
-keepclasseswithmembernames,includedescriptorclasses class se.lublin.humla.audio.javacpp.** {
    native <methods>;
}

# JavaCPP Loader reads @Platform, @Name, @Cast, etc. at runtime. Inner-class
# metadata is also required for generated nested native wrapper classes.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod
