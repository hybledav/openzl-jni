# Test-build workaround for Abseil #1634 / GCC PR 71962. Do not include in
# normal release builds. ASan remains enabled on these dependency targets;
# both ASan and UBSan remain enabled on OpenZL and the JNI bridge.
if(CMAKE_CURRENT_SOURCE_DIR STREQUAL CMAKE_SOURCE_DIR)
  function(openzl_validation_sanitizer_workaround)
    foreach(target flags_reflection libprotobuf libprotoc)
      target_compile_options(${target} PRIVATE -fno-sanitize=undefined)
    endforeach()
  endfunction()
  cmake_language(DEFER CALL openzl_validation_sanitizer_workaround)
endif()
