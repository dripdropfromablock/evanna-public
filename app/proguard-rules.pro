# The decoder's C++ code finds these methods by name.
-keepclasseswithmembernames class com.evanna.player.decoder.NativeDecoder** { native <methods>; }

# Keep file and line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# SMB uses explicit NTLM authentication on Android. These optional Kerberos and
# MBassador expression-language paths have no runtime provider here.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep @interface net.engio.mbassy.listener.Handler
-keepclassmembers class com.hierynomus.smbj.** {
    @net.engio.mbassy.listener.Handler <methods>;
}
-keep class org.libtorrent4j.swig.** { *; }
