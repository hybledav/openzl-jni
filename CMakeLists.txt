cmake_minimum_required(VERSION 3.20.2 FATAL_ERROR)
project(openzl_jni_native LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(OPENZL_BUILD_TESTS OFF CACHE BOOL "Don't build OpenZL tests" FORCE)
set(OPENZL_BUILD_BENCHMARKS OFF CACHE BOOL "Don't build OpenZL benchmarks" FORCE)
set(OPENZL_BUILD_MODE opt CACHE STRING "OpenZL build mode" FORCE)

include(FetchContent)

FetchContent_Declare(
  openzl
  GIT_REPOSITORY https://github.com/facebook/openzl.git
  GIT_TAG v0.1.0
  GIT_SHALLOW TRUE
  GIT_PROGRESS TRUE
  GIT_SUBMODULES ""        
  GIT_SUBMODULES_RECURSE TRUE
)
FetchContent_MakeAvailable(openzl)

find_package(JNI REQUIRED)

set(OPENZL_JNI_DIR ${PROJECT_SOURCE_DIR}/JNI)

add_library(openzl_jni SHARED
    ${OPENZL_JNI_DIR}/OpenZLCompressor.cpp
)
set_target_properties(openzl_jni PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/cli"
)
target_include_directories(openzl_jni PRIVATE
    ${PROJECT_BINARY_DIR}/include
    ${PROJECT_SOURCE_DIR}/include
    ${PROJECT_SOURCE_DIR}/src
    ${PROJECT_SOURCE_DIR}
    ${OPENZL_JNI_DIR}
    ${JNI_INCLUDE_DIRS}
)
target_link_libraries(openzl_jni PRIVATE
    openzl_cpp
)
