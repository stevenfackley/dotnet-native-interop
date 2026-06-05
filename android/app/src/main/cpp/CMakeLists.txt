cmake_minimum_required(VERSION 3.22.1)
project(ondevicellm_jni C)

# ONDEVICELLM_INCLUDE_DIR is injected from app/build.gradle.kts externalNativeBuild arguments.
# It points to core/OnDeviceLlm.NativeBridge/abi/ which contains ondevicellm.h.
if(NOT DEFINED ONDEVICELLM_INCLUDE_DIR)
    message(FATAL_ERROR "ONDEVICELLM_INCLUDE_DIR not set — pass via externalNativeBuild.cmake.arguments")
endif()

add_library(
    ondevicellm_jni   # the JNI shim (.so that Kotlin loads)
    SHARED
    jni_bridge.c
)

target_include_directories(ondevicellm_jni PRIVATE
    ${ONDEVICELLM_INCLUDE_DIR}
)

# Link against the pre-built NativeAOT .so (libondevicellm.so) which lives in
# app/src/main/jniLibs/arm64-v8a/.  Android's dynamic linker resolves it at
# runtime because jniLibs/ is added to the APK's lib/ directory automatically.
# We declare it as an IMPORTED target so the compiler resolves the ABI headers
# without needing the .so at CMake configure time.
find_library(log-lib log)
target_link_libraries(ondevicellm_jni
    ${log-lib}
    # Do NOT link ondevicellm here at link time; it is loaded first via
    # System.loadLibrary("ondevicellm") before System.loadLibrary("ondevicellm_jni")
    # in Kotlin, so all C symbols are already resident.
    # If the toolchain complains about undefined symbols, add:
    #   -Wl,--allow-shlib-undefined
)

target_compile_options(ondevicellm_jni PRIVATE -Wall -Wextra -Werror)
