# Reuse the pinned FFmpeg source for audio, MP4 output and media downloads.
include(ExternalProject)
set(_xml_prefix "${CMAKE_CURRENT_BINARY_DIR}/download-libxml2")
ExternalProject_Add(buzin_download_xml
  URL https://download.gnome.org/sources/libxml2/2.15/libxml2-2.15.4.tar.xz
  URL_HASH SHA256=98087fd181d9070724f3fbc65c7377db03038eb92bd882374daff44940138821
  DOWNLOAD_EXTRACT_TIMESTAMP FALSE
  CMAKE_ARGS
    -DCMAKE_INSTALL_PREFIX=${_xml_prefix} -DCMAKE_INSTALL_LIBDIR=lib
    -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_C_COMPILER=${CMAKE_C_COMPILER}
    -DCMAKE_AR=${CMAKE_AR} -DCMAKE_RANLIB=${CMAKE_RANLIB}
    "-DCMAKE_C_FLAGS=${CMAKE_C_FLAGS}" "-DCMAKE_EXE_LINKER_FLAGS=${CMAKE_EXE_LINKER_FLAGS}"
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF
    -DLIBXML2_WITH_PROGRAMS=OFF -DLIBXML2_WITH_TESTS=OFF -DLIBXML2_WITH_ICONV=OFF
    -DLIBXML2_WITH_MODULES=OFF -DLIBXML2_WITH_CATALOG=OFF -DLIBXML2_WITH_XINCLUDE=OFF
  # Install inside the build step so Ninja observes changes to the archives it links.
  BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --config Release --parallel 2
    COMMAND ${CMAKE_COMMAND} -E env --unset=DESTDIR ${CMAKE_COMMAND} --install <BINARY_DIR> --config Release
  INSTALL_COMMAND ""
  BUILD_BYPRODUCTS "${_xml_prefix}/lib/libxml2.a"
  LOG_CONFIGURE TRUE LOG_BUILD TRUE LOG_INSTALL TRUE)

if(CMAKE_SYSTEM_PROCESSOR STREQUAL "armv7")
  set(_alac_arch arm)
else()
  set(_alac_arch "${CMAKE_SYSTEM_PROCESSOR}")
endif()
set(_alac_prefix "${CMAKE_CURRENT_BINARY_DIR}/alac-ffmpeg")
file(MAKE_DIRECTORY "${_alac_prefix}/include")
ExternalProject_Add(buzin_alac_ffmpeg
  DEPENDS buzin_openssl buzin_download_xml
  SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp/third_party/ffmpeg"
  DOWNLOAD_COMMAND "" UPDATE_COMMAND ""
  CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env
    "PKG_CONFIG_LIBDIR=${_xml_prefix}/lib/pkgconfig:${_crypto_prefix}/lib/pkgconfig" PKG_CONFIG_PATH=
    bash <SOURCE_DIR>/configure
    --prefix=${_alac_prefix} --target-os=linux --arch=${_alac_arch} --enable-cross-compile
    --cc=${CMAKE_C_COMPILER} --ar=${CMAKE_AR} --ranlib=${CMAKE_RANLIB} --nm=${CMAKE_NM}
    "--extra-cflags=${CMAKE_C_FLAGS} -I${_xml_prefix}/include -fPIC -O2"
    "--extra-ldflags=${CMAKE_C_FLAGS} ${CMAKE_EXE_LINKER_FLAGS}"
    --enable-pic --disable-asm --disable-x86asm --disable-all --disable-debug
    --enable-network --disable-autodetect --disable-doc --disable-programs
    --enable-version3 --enable-openssl --enable-libxml2 --pkg-config-flags=--static
    --enable-protocol=http,https,tcp,tls,crypto
    --enable-demuxer=mov,hls,dash,mpegts --enable-parser=h264,hevc,aac --enable-bsf=extract_extradata
    --enable-avcodec --enable-decoder=alac,aac,h264 --enable-encoder=aac --enable-avformat --enable-muxer=mp4 --enable-static --disable-shared
  BUILD_COMMAND ${BUZIN_MAKE} -j2
    COMMAND ${BUZIN_MAKE} DESTDIR= install
  INSTALL_COMMAND ""
  BUILD_BYPRODUCTS "${_alac_prefix}/lib/libavcodec.a" "${_alac_prefix}/lib/libavutil.a" "${_alac_prefix}/lib/libavformat.a"
  LOG_CONFIGURE TRUE LOG_BUILD TRUE LOG_INSTALL TRUE)
add_library(BuzinAlacDecoder STATIC
  kepler/turbo-modules/AlacDecoder.cpp kepler/turbo-modules/AacPcmDecoder.cpp)
add_dependencies(BuzinAlacDecoder buzin_alac_ffmpeg)
target_include_directories(BuzinAlacDecoder PRIVATE "${_alac_prefix}/include")
target_link_libraries(BuzinAlacDecoder PUBLIC "${_alac_prefix}/lib/libavcodec.a" "${_alac_prefix}/lib/libavutil.a" m pthread)

add_library(BuzinVideoMuxer STATIC kepler/turbo-modules/H264Fmp4Muxer.cpp
  kepler/turbo-modules/AlacAacTranscoder.cpp
  kepler/turbo-modules/PcmAacEncoder.cpp)
add_dependencies(BuzinVideoMuxer buzin_alac_ffmpeg)
target_include_directories(BuzinVideoMuxer PRIVATE "${_alac_prefix}/include")
target_link_libraries(BuzinVideoMuxer PUBLIC "${_alac_prefix}/lib/libavformat.a"
  BuzinAlacDecoder buzin_ssl "${_xml_prefix}/lib/libxml2.a")

add_library(BuzinMediaDownload STATIC kepler/turbo-modules/MediaDownload.cpp)
add_dependencies(BuzinMediaDownload buzin_alac_ffmpeg)
target_compile_definitions(BuzinMediaDownload PRIVATE _FILE_OFFSET_BITS=64)
target_include_directories(BuzinMediaDownload PRIVATE "${_alac_prefix}/include")
target_link_libraries(BuzinMediaDownload PUBLIC BuzinVideoMuxer)

add_library(BuzinVideoOutput STATIC kepler/turbo-modules/Fmp4VideoOutput.cpp
  kepler/turbo-modules/LocalVideoStream.cpp
  kepler/turbo-modules/MirrorRecording.cpp)
target_link_libraries(BuzinVideoOutput PUBLIC BuzinVideoMuxer)
