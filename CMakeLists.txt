cmake_minimum_required(VERSION 3.20.2 FATAL_ERROR)
project(openzl_jni_native LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(OPENZL_BUILD_TESTS OFF CACHE BOOL "Don't build OpenZL tests" FORCE)
set(OPENZL_BUILD_BENCHMARKS OFF CACHE BOOL "Don't build OpenZL benchmarks" FORCE)
set(OPENZL_BUILD_MODE opt CACHE STRING "OpenZL build mode" FORCE)
set(OPENZL_BUILD_TOOLS ON CACHE BOOL "Build OpenZL tools for JNI" FORCE)

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

if(TARGET custom_parsers AND TARGET pytorch_model_parser)
    target_link_libraries(custom_parsers pytorch_model_parser)
endif()

set(OPENZL_LOGGER_STRING_SHIM "${PROJECT_SOURCE_DIR}/cmake/shims/logger_string_compat.h")
if(TARGET logger AND EXISTS "${OPENZL_LOGGER_STRING_SHIM}")
    if(MSVC)
        target_compile_options(logger PRIVATE "/FI${OPENZL_LOGGER_STRING_SHIM}")
    else()
        target_compile_options(logger PRIVATE "-include${OPENZL_LOGGER_STRING_SHIM}")
    endif()
endif()

set(OPENZL_TOOLS_IO_SHIM "${PROJECT_SOURCE_DIR}/cmake/shims/tools_io_stdexcept_compat.h")
if(TARGET tools_io AND EXISTS "${OPENZL_TOOLS_IO_SHIM}")
    if(MSVC)
        target_compile_options(tools_io PRIVATE "/FI${OPENZL_TOOLS_IO_SHIM}")
    else()
        target_compile_options(tools_io PRIVATE "-include${OPENZL_TOOLS_IO_SHIM}")
    endif()
endif()

set(OPENZL_JNI_DIR ${PROJECT_SOURCE_DIR}/JNI)

add_library(openzl_jni SHARED
    ${OPENZL_JNI_DIR}/OpenZLCompressor.cpp
    ${OPENZL_JNI_DIR}/OpenZLNativeSupport.cpp
    ${OPENZL_JNI_DIR}/compressor/OpenZLCompressorArrays.cpp
    ${OPENZL_JNI_DIR}/compressor/OpenZLCompressorDirect.cpp
    ${OPENZL_JNI_DIR}/compressor/OpenZLCompressorMetadata.cpp
    ${OPENZL_JNI_DIR}/compressor/OpenZLCompressorNumeric.cpp
    ${openzl_SOURCE_DIR}/cli/utils/compress_profiles.cpp
)
set_target_properties(openzl_jni PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/cli"
)
target_include_directories(openzl_jni PRIVATE
    ${PROJECT_BINARY_DIR}/include
    ${PROJECT_SOURCE_DIR}/include
    ${PROJECT_SOURCE_DIR}/src
    ${PROJECT_SOURCE_DIR}
    ${openzl_SOURCE_DIR}
    ${openzl_SOURCE_DIR}/include
    ${openzl_SOURCE_DIR}/tools
    ${openzl_BINARY_DIR}/include
    ${openzl_BINARY_DIR}/tools
    ${OPENZL_JNI_DIR}
    ${JNI_INCLUDE_DIRS}
)
target_link_libraries(openzl_jni PRIVATE
    openzl_cpp
    custom_parsers
    sddl_profile
    sddl_compiler_lib
    tools_io
    tools_training
)
