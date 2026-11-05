# Protocol sources are the same pinned submodules used by Fire OS. No JNI,
# Android codecs, Oboe or Android NSD are pulled into this target.
include(ExternalProject)
find_program(BUZIN_PERL perl REQUIRED)
find_program(BUZIN_MAKE make REQUIRED)
set(_crypto_prefix "${CMAKE_CURRENT_BINARY_DIR}/openssl-vega")
if(CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64")
  set(_openssl_target linux-aarch64)
elseif(CMAKE_SYSTEM_PROCESSOR STREQUAL "armv7")
  set(_openssl_target linux-armv4)
elseif(CMAKE_SYSTEM_PROCESSOR STREQUAL "x86_64")
  set(_openssl_target linux-x86_64)
else()
  message(FATAL_ERROR "Unsupported Vega CPU for OpenSSL: ${CMAKE_SYSTEM_PROCESSOR}")
endif()
file(MAKE_DIRECTORY "${_crypto_prefix}/include")
set(_crypto_environment
  "CC=${CMAKE_C_COMPILER}"
  "AR=${CMAKE_AR}"
  "RANLIB=${CMAKE_RANLIB}"
  "CFLAGS=${CMAKE_C_FLAGS} -fPIC -O2"
  "LDFLAGS=${CMAKE_EXE_LINKER_FLAGS}")
ExternalProject_Add(buzin_openssl
  URL https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz
  URL_HASH SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
  DOWNLOAD_EXTRACT_TIMESTAMP FALSE
  UPDATE_COMMAND ""
  CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env ${_crypto_environment}
    ${BUZIN_PERL} <SOURCE_DIR>/Configure ${_openssl_target}
    no-shared no-module no-apps no-tests no-docs no-asm no-fips
    --prefix=${_crypto_prefix} --libdir=lib
  BUILD_COMMAND ${CMAKE_COMMAND} -E env ${_crypto_environment} ${BUZIN_MAKE} -j2 build_libs
  INSTALL_COMMAND ${CMAKE_COMMAND} -E env ${_crypto_environment} ${BUZIN_MAKE} install_dev
  BUILD_BYPRODUCTS "${_crypto_prefix}/lib/libcrypto.a")
add_library(buzin_crypto STATIC IMPORTED)
set_target_properties(buzin_crypto PROPERTIES
  IMPORTED_LOCATION "${_crypto_prefix}/lib/libcrypto.a"
  INTERFACE_INCLUDE_DIRECTORIES "${_crypto_prefix}/include")
add_dependencies(buzin_crypto buzin_openssl)

set(_third_party "${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp/third_party")
set(_plist "${_third_party}/libplist")
set(_ux "${_third_party}/UxPlay/lib")
set(_plist_files common base64 bplist bytearray hashtable jplist jsmn oplist
  out-default out-limd out-plutil plist ptrarray time64 xplist)
set(_plist_sources)
foreach(_file IN LISTS _plist_files)
  list(APPEND _plist_sources "${_plist}/src/${_file}.c")
endforeach()
add_library(buzin_plist STATIC ${_plist_sources}
  "${_plist}/libcnary/cnary.c" "${_plist}/libcnary/node.c" "${_plist}/libcnary/node_list.c")
target_include_directories(buzin_plist PUBLIC "${_plist}/include"
  PRIVATE "${_plist}/src" "${_plist}/libcnary/include")
target_compile_definitions(buzin_plist PRIVATE _GNU_SOURCE HAVE_STRNDUP PACKAGE_VERSION="2.8.0")

add_library(buzin_llhttp STATIC "${_ux}/llhttp/api.c" "${_ux}/llhttp/http.c" "${_ux}/llhttp/llhttp.c")
target_include_directories(buzin_llhttp PUBLIC "${_ux}/llhttp")
add_library(buzin_playfair STATIC
  "${_ux}/playfair/hand_garble.c" "${_ux}/playfair/modified_md5.c"
  "${_ux}/playfair/omg_hax.c" "${_ux}/playfair/playfair.c" "${_ux}/playfair/sap_hash.c")
target_include_directories(buzin_playfair PUBLIC "${_ux}/playfair")
set(_raop_files airplay_video byteutils compat crypto fairplay_playfair http_request
  http_response httpd logger mirror_buffer netutils pairing raop raop_buffer
  raop_ntp raop_rtp raop_rtp_mirror srp utils)
set(_raop_sources)
foreach(_file IN LISTS _raop_files)
  list(APPEND _raop_sources "${_ux}/${_file}.c")
endforeach()
# DNS-SD is an adapter boundary. An implementation must be supplied by the
# final receiver before exposing a protocol service; there is no fake announcer.
add_library(BuzinAirPlayCore STATIC ${_raop_sources})
target_include_directories(BuzinAirPlayCore PUBLIC "${_ux}")
target_compile_definitions(BuzinAirPlayCore PRIVATE _GNU_SOURCE PLIST_210 TARGET_RT_LITTLE_ENDIAN=1)
target_link_libraries(BuzinAirPlayCore PUBLIC buzin_crypto buzin_plist buzin_llhttp buzin_playfair pthread m)
