# The decoder's C++ code finds these methods by name.
-keepclasseswithmembernames class com.evanna.player.decoder.NativeDecoder** { native <methods>; }

# Keep file and line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
