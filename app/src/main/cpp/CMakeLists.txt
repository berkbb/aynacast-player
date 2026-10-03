cmake_minimum_required(VERSION 3.22.1)

project("airplaycore")

add_library(airplaycore SHARED
    airplay_jni.cpp
    airplay_crypto.cpp
    raop_rtsp_server.cpp
    h264_stream_parser.cpp
    tweetnacl.c
    aes.c
    alac.c
    playfair/hand_garble.c
    playfair/modified_md5.c
    playfair/omg_hax.c
    playfair/playfair.c
    playfair/sap_hash.c
)

find_library(log-lib log)
find_library(android-lib android)

target_include_directories(airplaycore PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(airplaycore
    ${log-lib}
    ${android-lib}
)
