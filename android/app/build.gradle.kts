import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Room KSP — add KSP plugin here if upgrading; for now use kapt-free Room via annotationProcessor
    id("kotlin-kapt")
    // Wave B Plan C: framed-protobuf (4th transport) client codegen. Re-introduces JUST the message
    // half (java + kotlin lite builtins, NO grpc/grpckt plugins — those stay excised per PR #40). The
    // live consumer this time is PbFeatureService/PbInferenceClient (the gRPC-era proto/dni.proto stays
    // the documented exclusion; this schema is src/main/proto/dni_frame.proto). Recipe proven in the
    // B0 gate (spike/android-client-deps).
    alias(libs.plugins.protobuf)
}

android {
    namespace = "io.dotnetnativeinterop"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.dotnetnativeinterop"
        minSdk = 29          // Android 10 — UDS + OkHttp reliable floor
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Neural RAG (download-on-first-run): the canonical public GGUF for byte-identical parity
        // with iOS's bundled copy (both should trace back to this exact HF repo/file — see
        // core/DotnetNativeInterop.Engine's own bundling notes for the "reputable GGUF repo" this
        // was sourced from). GGUF_SHA256 empty = skip the optional strict-hash check; the GGUF-magic
        // header check always runs, and the engine's own LlamaLanguageModel load is the ultimate gate.
        buildConfigField(
            "String",
            "GGUF_URL",
            "\"https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf\"",
        )
        buildConfigField("String", "GGUF_SHA256", "\"\"")

        ndk {
            // Build only arm64-v8a; add x86_64 for emulator if desired
            abiFilters += setOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Pass the ABI header location so jni_bridge.c can include it
                arguments(
                    "-DDNI_INCLUDE_DIR=${rootDir.parentFile.absolutePath}/core/DotnetNativeInterop.NativeBridge/abi"
                )
                cFlags("-std=c11", "-Wall", "-Wextra")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Pre-built libdni.so goes in jniLibs — see build/build-android-so.sh
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            assets.srcDirs("src/main/assets")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Explicit API mode: all public declarations require visibility modifiers
        freeCompilerArgs += listOf("-Xexplicit-api=strict")
    }

    androidResources {
        noCompress += "onnx"   // store model.onnx uncompressed so ORT can read it from the extracted copy
    }

    packaging {
        // libonnxruntime.so comes solely from the onnxruntime-android AAR (1.20.0). The build script
        // (build/build-android-so.sh) no longer copies a jniLibs copy alongside libdni.so; libdni.so
        // uses dlopen (no DT_NEEDED) and resolves the AAR-provided .so at runtime. libonnxruntime4j_jni.so
        // (Kotlin EVS JNI bridge) references @VERS_1.20.0 versioned symbols — compatible with 1.20.0 only.
        // If a jniLibs copy of libonnxruntime.so re-appears (e.g. re-running an old build script),
        // pickFirsts keeps one silently; Gradle warnings will indicate which version was selected.
        jniLibs.pickFirsts += "lib/arm64-v8a/libonnxruntime.so"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// NOTE: gRPC/UDS remains an EXCLUDED transport (no NativeAOT mobile runtime pack). Its Kotlin client
// (GrpcUdsClient) and the grpc/grpckt protoc plugins stay removed; proto/dni.proto + patterns.json's
// "grpc" entry keep documenting that dead-end. Wave B re-adds ONLY the message-codegen half below for
// the NEW framed-protobuf transport (dni_frame.proto) — structured binary RPC with no gRPC runtime.

// ---------------------------------------------------------------------------
// Framed-protobuf client codegen (Wave B Plan C). protobuf-kotlin-lite: java + kotlin lite builtins
// only (no grpc/grpckt). The Kotlin DSL wraps the Java lite message classes, so BOTH builtins must be
// present in lite mode or the generated Kotlin fails to resolve its message types.
// ---------------------------------------------------------------------------
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") { option("lite") }
                id("kotlin") { option("lite") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Copy patterns.json into assets at build time
// ---------------------------------------------------------------------------
tasks.register<Copy>("copyPatternsJson") {
    from("${rootDir.parentFile.absolutePath}/docs/patterns.json")
    into("${projectDir}/src/main/assets")
}
tasks.named("preBuild") { dependsOn("copyPatternsJson") }

tasks.register<Copy>("copyAiAssets") {
    from("${rootDir.parentFile.absolutePath}/core/DotnetNativeInterop.Engine/Ai/assets") {
        include("model.onnx", "vocab.txt", "corpus.txt", "manuals/**")
    }
    from("${rootDir.parentFile.absolutePath}/core/DotnetNativeInterop.EdgeIndexPublisher") {
        include("edge-index.db")
    }
    into("${projectDir}/src/main/assets/dni-assets")
}
tasks.named("preBuild") { dependsOn("copyAiAssets") }

dependencies {
    // Compose BOM + core UI
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    // Per-tab navigation icons (BOM-managed; R8 strips unused glyphs in release).
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle / ViewModel
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Coroutines
    implementation(libs.coroutines.android)

    // OkHttp (HTTP SSE)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Room (SQLite transport)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // Edge Vector Search — app-layer ONNX (Kotlin); 1.20.0 is the closest AAR on Maven Central to
    // the engine's 1.20.1 (1.20.1 was never published for Android); same minor, compatible ABI.
    implementation(libs.onnxruntime.android)

    // JSON (patterns.json parsing)
    implementation(libs.kotlinx.serialization.json)

    // Wave B Plan C — framed-protobuf transport client + PQ handshake.
    //  - protobuf-kotlin-lite: the generated dni_frame.proto messages + Kotlin builder DSL.
    //  - bcprov: ML-KEM-768 / ML-DSA-65 via the LIGHTWEIGHT org.bouncycastle.crypto.* API ONLY. The
    //    JCA "BC" provider is deliberately never registered — Android ships a crippled legacy BC under
    //    that same name whose PQC classes don't exist; the lightweight classes sidestep it entirely
    //    (mirrors the .NET provider's Org.BouncyCastle.Crypto.* calls and the B0 gate's probe).
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.bouncycastle.bcprov)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Fake HTTP server for GgufDownloader tests (resume/Range, truncation, cancel) — no device needed.
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented test (SP0 native gate) — runs on the arm64 emulator
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)

    // SP1 shell smoke test — Compose UI test rule
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
