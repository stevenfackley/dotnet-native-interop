package io.dotnetnativeinterop

import android.app.Application
import android.util.Log
import io.dotnetnativeinterop.transport.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class — responsible for the native library load order and
 * background engine initialization.
 *
 * Load order is critical:
 *   1. "dni"     — the NativeAOT .so; must be resident before the JNI shim.
 *   2. "dni_jni" — the C shim that calls dni's C exports.
 *
 * If libdni.so is missing from jniLibs/ (i.e. the .NET build has not run
 * yet), the loadLibrary call throws UnsatisfiedLinkError.  The POC catches it and
 * logs a warning rather than crashing so the UI can still render.
 */
public class DotnetNativeInteropApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        loadNativeLibraries()
    }

    private fun loadNativeLibraries() {
        runCatching {
            // 1. NativeAOT shared library — all C symbols must be resident first.
            System.loadLibrary("dni")
        }.onFailure {
            Log.e(TAG, "Failed to load libdni.so — run build/build-android-so.sh first", it)
            return  // Cannot load the JNI shim if the ABI lib is absent.
        }

        runCatching {
            // 2. JNI shim that wraps the C ABI for Kotlin.
            System.loadLibrary("dni_jni")
        }.onFailure {
            Log.e(TAG, "Failed to load libdni_jni.so", it)
            return
        }

        // Initialize the engine on a background coroutine — never block the main thread.
        appScope.launch {
            runCatching {
                val assetsDir = AssetExtractor.ensure(this@DotnetNativeInteropApp)
                val setRc = NativeBridge.nativeSetAssetsDir(assetsDir.absolutePath)
                Log.i(TAG, "assets dir set rc=$setRc (${assetsDir.absolutePath})")
                val status = NativeBridge.nativeInitialize()
                if (status == 0) {
                    Log.i(TAG, "Engine initialized successfully")
                } else {
                    Log.e(TAG, "Engine initialization failed: status $status")
                }
            }.onFailure {
                Log.e(TAG, "engine init / asset extraction threw", it)
            }
        }
    }

    private companion object {
        private const val TAG = "DotnetNativeInteropApp"
    }
}
