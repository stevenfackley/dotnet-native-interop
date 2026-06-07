cmake_minimum_required(VERSION 3.22.1)
project(dni_jni C)

# DNI_INCLUDE_DIR is injected from app/build.gradle.kts externalNativeBuild arguments.
# It points to core/DotnetNativeInterop.NativeBridge/abi/ which contains dni.h.
if(NOT DEFINED DNI_INCLUDE_DIR)
    message(FATAL_ERROR "DNI_INCLUDE_DIR not set — pass via externalNativeBuild.cmake.arguments")
endif()

add_library(
    dni_jni   # the JNI shim (.so that Kotlin loads)
    SHARED
    jni_bridge.c
)

target_include_directories(dni_jni PRIVATE
    ${DNI_INCLUDE_DIR}
)

# Import the prebuilt NativeAOT library (built by build/build-android-so.sh into
# jniLibs/<abi>/libdni.so). Linking against it resolves the dni_* C ABI symbols at
# link time, satisfying the NDK toolchain's -Wl,--no-undefined. At runtime the loader
# pulls libdni.so in as a NEEDED dependency (it is also explicitly loaded first via
# System.loadLibrary("dni") before System.loadLibrary("dni_jni")).
add_library(dni SHARED IMPORTED)
set_target_properties(dni PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libdni.so)

find_library(log-lib log)
target_link_libraries(dni_jni
    ${log-lib}
    dni
)

target_compile_options(dni_jni PRIVATE -Wall -Wextra -Werror)
