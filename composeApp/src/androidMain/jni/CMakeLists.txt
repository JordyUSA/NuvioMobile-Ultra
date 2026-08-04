# Builds libmediainfo_jni.so, the JNI shim in this directory, against a MediaInfoLib and ZenLib
# that the caller has already cross-compiled and installed.
#
# Invoked only by .github/workflows/build-mediainfo.yml with the NDK toolchain file. Gradle never
# runs this: the app consumes the finished AAR, so a normal checkout needs no NDK.
#
# The MediaInfo libraries are passed in as absolute paths rather than found here, because their
# install layout differs between MediaInfoLib releases and the workflow already had to locate
# them to know the build succeeded.

cmake_minimum_required(VERSION 3.22)
project(mediainfo_jni CXX)

set(MEDIAINFO_INCLUDE_DIRS "" CACHE STRING "Include directories holding MediaInfo/ and ZenLib/")
set(MEDIAINFO_LIBRARIES "" CACHE STRING "Absolute paths of the static MediaInfoLib and ZenLib archives")

if(MEDIAINFO_INCLUDE_DIRS STREQUAL "" OR MEDIAINFO_LIBRARIES STREQUAL "")
    message(FATAL_ERROR "Set both MEDIAINFO_INCLUDE_DIRS and MEDIAINFO_LIBRARIES")
endif()

add_library(mediainfo_jni SHARED mediainfo_jni.cpp)

target_compile_features(mediainfo_jni PRIVATE cxx_std_17)
target_include_directories(mediainfo_jni PRIVATE ${MEDIAINFO_INCLUDE_DIRS})

# zlib comes from the NDK sysroot; MediaInfoLib needs it for compressed Matroska headers.
target_link_libraries(mediainfo_jni PRIVATE ${MEDIAINFO_LIBRARIES} z log)

# MediaInfoLib is large and almost none of it is reachable from one entry point. Keeping only
# what the linker can reach takes the per-ABI payload from tens of megabytes to a few.
target_link_options(mediainfo_jni PRIVATE
    "-Wl,--gc-sections"
    "-Wl,--exclude-libs,ALL"
)
