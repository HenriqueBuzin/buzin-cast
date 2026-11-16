cmake_minimum_required(VERSION 3.19)
project(BuzinNetwork VERSION 1.0 LANGUAGES CXX C)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
find_package(turbomoduleAPI CONFIG REQUIRED)
kepler_add_turbo_module_library(BuzinNetwork SHARED
  kepler/turbo-modules/generated/BuzinNetworkSpec.cpp
  kepler/turbo-modules/BuzinNetwork.cpp
  kepler/turbo-modules/ReceiverSettings.cpp
  kepler/turbo-modules/FCastTransport.cpp
  kepler/turbo-modules/FCastDiscovery.cpp
  kepler/turbo-modules/NetworkProbe.cpp
  kepler/AutoLinkInit.cpp)
target_link_options(BuzinNetwork PRIVATE "LINKER:--no-undefined" "LINKER:-z,defs")
apmf_add_idl_import_library(BuzinMediaIdl PACKAGES com.amazon.kepler.w3cmedia)
target_link_libraries(BuzinNetwork PRIVATE BuzinMediaIdl)
target_sources(BuzinNetwork PRIVATE kepler/turbo-modules/MsseAudioPlayer.cpp)
install(TARGETS BuzinNetwork)
# Small diagnostic runner for component-shell integration tests, using the same
# implementation as the UI. It has no privileges beyond the app sandbox.
add_executable(buzin-network-probe
  kepler/network_probe_main.cpp kepler/turbo-modules/NetworkProbe.cpp)
install(TARGETS buzin-network-probe RUNTIME DESTINATION bin)

include(cmake/AirPlayCore.cmake)
target_sources(BuzinNetwork PRIVATE kepler/turbo-modules/ProtocolProbe.cpp
  kepler/turbo-modules/AirPlaySession.cpp
  "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp/android_dnssd_shim.c")
target_link_libraries(BuzinNetwork PRIVATE BuzinAirPlayCore)
target_link_options(BuzinNetwork PRIVATE "LINKER:--exclude-libs,ALL")

# Development-only runner, excluded from the VPKG like the network probe.
add_executable(buzin-msse-probe kepler/msse_probe_main.cpp
  kepler/turbo-modules/MsseAudioPlayer.cpp)
target_link_libraries(buzin-msse-probe PRIVATE BuzinMediaIdl)
install(TARGETS buzin-msse-probe RUNTIME DESTINATION bin)
