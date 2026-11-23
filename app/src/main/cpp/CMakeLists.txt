cmake_minimum_required(VERSION 3.22.1)
project(cineboxfp LANGUAGES C CXX)

# Chromaprint 1.5.1 as a static library, with its bundled Kiss FFT and without FFmpeg.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(FFT_LIB "kissfft" CACHE STRING "" FORCE)
# FindKissFFT's find_path cannot see the bundled copy: the Android toolchain confines searches
# to the NDK sysroot. A preset KISSFFT_SOURCE_DIR makes find_path skip the search.
set(KISSFFT_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/chromaprint/src/3rdparty/kissfft" CACHE PATH "" FORCE)
# The static library ends up inside a shared one.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(chromaprint EXCLUDE_FROM_ALL)

add_library(cineboxfp SHARED chromaprint_jni.cpp)
target_include_directories(cineboxfp PRIVATE chromaprint/src)
target_link_libraries(cineboxfp PRIVATE chromaprint log)
