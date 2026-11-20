cmake_minimum_required(VERSION 3.22.1)
project(fireos3_probe C CXX)

# Android 4.2 x86 images can still use TLS slot 5 for pthread keys. Modern
# x86 compilers read that slot as the stack canary, which changes during
# OpenSSL's first pthread_setspecific and aborts a valid call. Keep SSP and
# use Bionic's stable global guard on this historical target, as on ARM32.
set(receiver_binary_dir production-core)
if(ANDROID_ABI STREQUAL "x86" AND CMAKE_SYSTEM_VERSION VERSION_LESS "18")
    string(APPEND CMAKE_C_FLAGS " -mstack-protector-guard=global")
    string(APPEND CMAKE_CXX_FLAGS " -mstack-protector-guard=global")
    # openssl-cmake treats an existing install prefix as immutable, even when
    # CFLAGS changed. Do not accidentally reuse its former TLS-guard build.
    set(receiver_binary_dir production-core-global-stack-guard)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a" AND CMAKE_SYSTEM_VERSION VERSION_LESS "18")
    # Use a separate OpenSSL build prefix for the software-only ARM variant.
    set(receiver_binary_dir production-core-arm-no-asm)
endif()

set(BUZIN_RAOP_CORE_ONLY ON)
set(BUZIN_RAOP_LEGACY_AUDIO ON)
add_subdirectory(../../../../app/src/main/cpp ${receiver_binary_dir})
add_library(fireos3_probe SHARED probe.c legacy_audio.cpp legacy_tls.c legacy_relay_crypto.c legacy_pcm_convert.cpp)
set_target_properties(fireos3_probe PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_definitions(fireos3_probe PRIVATE BUZIN_SOFTWARE_AUDIO_ONLY)
add_dependencies(fireos3_probe ffmpeg_ep)
target_include_directories(fireos3_probe PRIVATE ../../../../app/src/main/cpp)
target_link_libraries(fireos3_probe PRIVATE airplay_native llhttp ffmpeg::avcodec ffmpeg::swresample ffmpeg::avutil crypto ssl log)
target_link_options(fireos3_probe PRIVATE "-Wl,--exclude-libs,libcrypto.a")
target_link_options(fireos3_probe PRIVATE "-Wl,--exclude-libs,libssl.a")
