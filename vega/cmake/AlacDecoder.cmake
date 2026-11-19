# Reuse the pinned FFmpeg source for ALAC playback and the mirror MP4 output.
include(ExternalProject)
if(CMAKE_SYSTEM_PROCESSOR STREQUAL "armv7")
  set(_alac_arch arm)
else()
  set(_alac_arch "${CMAKE_SYSTEM_PROCESSOR}")
endif()
set(_alac_prefix "${CMAKE_CURRENT_BINARY_DIR}/alac-ffmpeg")
file(MAKE_DIRECTORY "${_alac_prefix}/include")
ExternalProject_Add(buzin_alac_ffmpeg
  SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp/third_party/ffmpeg"
  DOWNLOAD_COMMAND "" UPDATE_COMMAND ""
  CONFIGURE_COMMAND bash <SOURCE_DIR>/configure
    --prefix=${_alac_prefix} --target-os=linux --arch=${_alac_arch} --enable-cross-compile
    --cc=${CMAKE_C_COMPILER} --ar=${CMAKE_AR} --ranlib=${CMAKE_RANLIB} --nm=${CMAKE_NM}
    "--extra-cflags=${CMAKE_C_FLAGS} -fPIC -O2" "--extra-ldflags=${CMAKE_C_FLAGS} ${CMAKE_EXE_LINKER_FLAGS}"
    --enable-pic --disable-asm --disable-x86asm --disable-all --disable-debug
    --disable-network --disable-autodetect --disable-doc --disable-programs
    --enable-avcodec --enable-decoder=alac --enable-encoder=aac --enable-avformat --enable-muxer=mp4 --enable-static --disable-shared
  BUILD_COMMAND ${BUZIN_MAKE} -j2
  INSTALL_COMMAND ${BUZIN_MAKE} DESTDIR= install
  BUILD_BYPRODUCTS "${_alac_prefix}/lib/libavcodec.a" "${_alac_prefix}/lib/libavutil.a" "${_alac_prefix}/lib/libavformat.a"
  LOG_CONFIGURE TRUE LOG_BUILD TRUE LOG_INSTALL TRUE)
add_library(BuzinAlacDecoder STATIC kepler/turbo-modules/AlacDecoder.cpp)
add_dependencies(BuzinAlacDecoder buzin_alac_ffmpeg)
target_include_directories(BuzinAlacDecoder PRIVATE "${_alac_prefix}/include")
target_link_libraries(BuzinAlacDecoder PUBLIC "${_alac_prefix}/lib/libavcodec.a" "${_alac_prefix}/lib/libavutil.a" m pthread)

add_library(BuzinVideoMuxer STATIC kepler/turbo-modules/H264Fmp4Muxer.cpp
  kepler/turbo-modules/AlacAacTranscoder.cpp)
add_dependencies(BuzinVideoMuxer buzin_alac_ffmpeg)
target_include_directories(BuzinVideoMuxer PRIVATE "${_alac_prefix}/include")
target_link_libraries(BuzinVideoMuxer PUBLIC "${_alac_prefix}/lib/libavformat.a" BuzinAlacDecoder)

add_library(BuzinVideoOutput STATIC kepler/turbo-modules/Fmp4VideoOutput.cpp
  kepler/turbo-modules/LocalVideoStream.cpp)
target_link_libraries(BuzinVideoOutput PUBLIC BuzinVideoMuxer)
