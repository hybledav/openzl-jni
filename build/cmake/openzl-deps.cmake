# Copyright (c) Meta Platforms, Inc. and affiliates.

if (OPENZL_SANITIZE_ADDRESS)
    if (("${CMAKE_CXX_COMPILER_ID}" MATCHES GNU) OR ("${CMAKE_CXX_COMPILER_ID}" MATCHES Clang))
        set(OPENZL_SANITIZE_ADDRESS ON)
        set(OPENZL_ASAN_FLAGS -fsanitize=address,undefined)
        list(APPEND OPENZL_COMMON_FLAGS ${OPENZL_ASAN_FLAGS})
    endif()
endif()

if (OPENZL_SANITIZE_MEMORY)
    if (("${CMAKE_CXX_COMPILER_ID}" MATCHES GNU) OR ("${CMAKE_CXX_COMPILER_ID}" MATCHES Clang))
        set(OPENZL_SANITIZE_MEMORY ON)
        set(OPENZL_MSAN_FLAGS -fsanitize=memory)
        list(APPEND OPENZL_COMMON_FLAGS ${OPENZL_MSAN_FLAGS})
    endif()
endif()

set(ZSTD_LEGACY_SUPPORT OFF)

# Use git submodule instead of direct download
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/deps/zstd/build/cmake/CMakeLists.txt")
    execute_process(
        COMMAND git submodule update --init --recursive deps/zstd
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
        RESULT_VARIABLE GIT_SUBMOD_RESULT
    )
    if(NOT GIT_SUBMOD_RESULT EQUAL "0")
        message(FATAL_ERROR "git submodule update --init failed with ${GIT_SUBMOD_RESULT}, please checkout submodules manually")
    endif()
endif()

# Set zstd build options before making it available
set(ZSTD_BUILD_PROGRAMS OFF CACHE BOOL "")
set(ZSTD_BUILD_CONTRIB OFF CACHE BOOL "")
set(ZSTD_BUILD_TESTS OFF CACHE BOOL "")

# Add zstd subdirectory directly instead of using FetchContent
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/deps/zstd/build/cmake" zstd_build)
# Note: find_package not needed when using add_subdirectory - targets are directly available
list(APPEND OPENZL_LINK_LIBRARIES libzstd)

find_library(MATH_LIBRARY m)
if(MATH_LIBRARY)
    list(APPEND OPENZL_LINK_LIBRARIES m)
endif()

# We aren't currently using pthreads, but we expect to, so lets just include it
# now.
# Add it after Zstd because it is incorrectly not linking against Threads
set(CMAKE_THREAD_PREFER_PTHREAD ON)
set(THREADS_PREFER_PTHREAD_FLAG ON)
find_package(Threads REQUIRED)
set(ZTRONG_HAVE_PTHREAD "${CMAKE_USE_PTHREADS_INIT}")
list(APPEND CMAKE_REQUIRED_LIBRARIES Threads::Threads)
list(APPEND OPENZL_LINK_LIBRARIES Threads::Threads)

add_library(openzl_deps INTERFACE)

list(REMOVE_DUPLICATES OPENZL_INCLUDE_DIRECTORIES)
target_include_directories(openzl_deps INTERFACE ${OPENZL_INCLUDE_DIRECTORIES})
target_link_libraries(openzl_deps INTERFACE
    ${OPENZL_LINK_LIBRARIES}
    ${OPENZL_ASAN_FLAGS}
    ${OPENZL_MSAN_FLAGS}
)
