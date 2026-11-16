cmake_minimum_required(VERSION 3.22.1)
project(fireos3_probe C CXX)

set(BUZIN_RAOP_CORE_ONLY ON)
add_subdirectory(../../../../app/src/main/cpp production-core)
add_library(fireos3_probe SHARED probe.c)
target_include_directories(fireos3_probe PRIVATE ../../../../app/src/main/cpp)
target_link_libraries(fireos3_probe PRIVATE airplay_native llhttp)
target_link_options(fireos3_probe PRIVATE "-Wl,--exclude-libs,libcrypto.a")
