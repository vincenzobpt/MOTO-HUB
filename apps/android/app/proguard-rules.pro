# MOTO-HUB R8 hardening — applied to the debug variant too, because the
# exported "public" APK is the debug build. Symbol maps land in
# app/build/outputs/mapping/<variant>/mapping.txt (keep them per release
# to retrace crash logs).

# Flatten every obfuscated class into the root package and hide the original
# source file names while keeping line numbers retraceable.
-repackageclasses
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# T-Box HUD bridge (hudlib.aar) is a gomobile binding: libgojni.so resolves
# these classes by name over JNI. Mirrors the AAR's own consumer rules, which
# Gradle does not reliably apply for local file dependencies.
-keep class go.** { *; }
-keep class api.** { *; }

# Conscrypt is bootstrapped reflectively (ConscryptInitializer uses
# Class.forName + getMethod), so nothing in it may be renamed or stripped.
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# The Android Auto protocol framing uses the full protobuf runtime, which
# reflects on its own internals (UnsafeUtil, descriptor lookups).
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Our own AA protocol messages (Media.Config, etc.) are GeneratedMessageV3
# subclasses too: their FieldAccessorTable resolves getters like getStatus()
# by reflection off the exact method name. If R8 renames those methods,
# toString()/TextFormat on any AA message throws NoSuchMethodException at
# runtime — which aborts protocol handling before the response is sent
# (e.g. mediaSinkSetupRequest never reaches aapTransport.send()).
-keep class io.motohub.android.aa.proto.** { *; }
