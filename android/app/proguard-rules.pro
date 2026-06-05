# OnDeviceLLM ProGuard rules

# Keep JNI entry points — the C shim calls back into these by name
-keep class io.ondevicellm.transport.FfiTokenListener { *; }
-keepclassmembers class io.ondevicellm.transport.FfiTokenListener {
    public void onToken(int, java.lang.String, boolean);
}

# Keep NativeBridge external functions (referenced by JNI)
-keep class io.ondevicellm.transport.NativeBridge { *; }

# Room — keep entity classes and DAOs
-keep class io.ondevicellm.data.** { *; }

# gRPC generated code
-keep class io.grpc.** { *; }
-keep class ondevicellm.v1.** { *; }
-dontwarn io.grpc.**

# OkHttp SSE
-dontwarn okhttp3.internal.**
-keep class okhttp3.internal.ws.** { *; }
-keep class okhttp3.sse.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
