import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    // Room KSP — add KSP plugin here if upgrading; for now use kapt-free Room via annotationProcessor
    id("kotlin-kapt")
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

    buildFeatures {
        compose = true
    }
}

// ---------------------------------------------------------------------------
// Protobuf plugin — generates Kotlin + gRPC stubs from ../../proto/
// ---------------------------------------------------------------------------
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpcJava.get()}"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpcKotlin.get()}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins {
                id("kotlin")
            }
            // Point at the shared proto directory
            task.protoSourceDirs.add(file("${rootDir.parentFile.absolutePath}/proto"))
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

dependencies {
    // Compose BOM + core UI
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
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

    // gRPC
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf.lite)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.kotlin.lite)

    // Room (SQLite transport)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // JSON (patterns.json parsing)
    implementation(libs.kotlinx.serialization.json)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
