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
  kepler/turbo-modules/CastV2Transport.cpp
  kepler/turbo-modules/RelayReceiver.cpp
  kepler/turbo-modules/RecordingExport.cpp
  kepler/turbo-modules/SubtitleUpload.cpp
  kepler/turbo-modules/DlnaTransport.cpp
  kepler/turbo-modules/MdnsDiscovery.cpp
  kepler/turbo-modules/NetworkProbe.cpp
  kepler/AutoLinkInit.cpp)
target_link_options(BuzinNetwork PRIVATE "LINKER:--no-undefined" "LINKER:-z,defs")
apmf_add_idl_import_library(BuzinMediaIdl PACKAGES com.amazon.kepler.w3cmedia)
target_link_libraries(BuzinNetwork PRIVATE BuzinMediaIdl)
target_sources(BuzinNetwork PRIVATE kepler/turbo-modules/MsseAudioPlayer.cpp
  kepler/turbo-modules/AirPlayAudioWorker.cpp kepler/turbo-modules/AirPlayVideoWorker.cpp)
install(TARGETS BuzinNetwork)
# Small diagnostic runner for component-shell integration tests, using the same
# implementation as the UI. It has no privileges beyond the app sandbox.
add_executable(buzin-network-probe
  kepler/network_probe_main.cpp kepler/turbo-modules/NetworkProbe.cpp)
install(TARGETS buzin-network-probe RUNTIME DESTINATION bin)
add_executable(buzin-dlna-discovery-probe test/DlnaDiscoveryProbe.cpp)
install(TARGETS buzin-dlna-discovery-probe RUNTIME DESTINATION bin)

include(cmake/AirPlayCore.cmake)
include(cmake/AlacDecoder.cmake)
apmf_add_idl_import_library(BuzinAudioIdl PACKAGES com.amazon.kepler.audio)
target_sources(BuzinNetwork PRIVATE kepler/turbo-modules/AlacAudioPlayer.cpp)
target_link_libraries(BuzinNetwork PRIVATE BuzinAudioIdl BuzinAlacDecoder BuzinVideoOutput BuzinMediaDownload)
target_sources(BuzinNetwork PRIVATE kepler/turbo-modules/ProtocolProbe.cpp
  kepler/turbo-modules/AirPlaySession.cpp
  "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp/android_dnssd_shim.c")
target_include_directories(BuzinNetwork PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp")
set_source_files_properties("${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp/android_dnssd_shim.c"
  PROPERTIES COMPILE_DEFINITIONS BUZIN_DNSSD_WIRE_TXT)
add_library(BuzinRelaySender STATIC kepler/turbo-modules/RelaySender.cpp
  kepler/turbo-modules/BuzinRelayWire.cpp kepler/turbo-modules/LocalTransfer.cpp)
target_link_libraries(BuzinRelaySender PUBLIC BuzinAlacDecoder buzin_crypto)
target_link_libraries(BuzinVideoOutput PUBLIC BuzinRelaySender)
target_link_libraries(BuzinNetwork PRIVATE BuzinAirPlayCore buzin_ssl)
target_link_options(BuzinNetwork PRIVATE "LINKER:--exclude-libs,ALL")

# Development-only runner, excluded from the VPKG like the network probe.
add_executable(buzin-msse-probe kepler/msse_probe_main.cpp
  kepler/turbo-modules/MsseAudioPlayer.cpp kepler/turbo-modules/AirPlayAudioWorker.cpp
  kepler/AirPlayRtpProbe.cpp kepler/turbo-modules/AlacAudioPlayer.cpp)
target_link_libraries(buzin-msse-probe PRIVATE BuzinMediaIdl BuzinAirPlayCore BuzinAudioIdl BuzinAlacDecoder)
install(TARGETS buzin-msse-probe RUNTIME DESTINATION bin)

# Development-only H.264/MSSE/mux/loopback probe, excluded from the VPKG.
add_executable(buzin-msse-video-probe kepler/msse_video_probe_main.cpp
  kepler/probes/MsseVideoPlayer.cpp kepler/turbo-modules/AirPlayVideoWorker.cpp kepler/AirPlayMirrorProbe.cpp)
target_link_libraries(buzin-msse-video-probe PRIVATE BuzinMediaIdl BuzinVideoOutput BuzinAirPlayCore)
install(TARGETS buzin-msse-video-probe RUNTIME DESTINATION bin)

# Development-only BZCAST02 receiver with the production media pipeline.
add_executable(buzin-relay-receiver-probe test/RelayReceiverProbe.cpp
  kepler/turbo-modules/RelayReceiver.cpp kepler/turbo-modules/AirPlayVideoWorker.cpp)
target_link_libraries(buzin-relay-receiver-probe PRIVATE BuzinVideoOutput buzin_crypto)
install(TARGETS buzin-relay-receiver-probe RUNTIME DESTINATION bin)
