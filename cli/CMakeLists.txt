# Copyright (c) Meta Platforms, Inc. and affiliates.

file(GLOB cli_root_sources
    CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/*.cpp")

file(GLOB cli_root_headers
    CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/*.h")

add_subdirectory(args)
add_subdirectory(commands)
add_subdirectory(utils)

if (BUILD_TESTS AND BUILD_PYTHON_EXT)
    message(STATUS "adding python tests")
    add_subdirectory(tests)
endif()

add_executable(zli
    ${cli_root_sources}
)
target_link_libraries(zli PRIVATE
    args
    commands
    utils
    logger
)

# there's gotta be a better way than manually specifying openzl/include/
target_include_directories(zli
    PRIVATE ${PROJECT_BINARY_DIR}/include
    ${PROJECT_SOURCE_DIR}/include
    ${PROJECT_SOURCE_DIR}/src
    ${PROJECT_SOURCE_DIR} )

add_dependencies(zli
    args
    commands
    utils
    logger
)

apply_openzl_compile_options_to_target(zli)

# JNI wrapper build
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
apply_openzl_compile_options_to_target(openzl_jni)
