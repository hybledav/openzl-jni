set(OPENZL_ML_SELECTOR_CMAKE "${OPENZL_SOURCE_DIR}/tools/ml_selector/CMakeLists.txt")
file(READ "${OPENZL_ML_SELECTOR_CMAKE}" OPENZL_ML_SELECTOR_TEXT)
set(OPENZL_ML_SELECTOR_ORIGINAL "${OPENZL_ML_SELECTOR_TEXT}")

string(REPLACE
[[    # On Windows/MSVC, static libraries don't have a "lib" prefix
    if(MSVC)
        set(XGBOOST_LIB_NAME "xgboost${CMAKE_STATIC_LIBRARY_SUFFIX}")
        set(DMLC_LIB_NAME "dmlc${CMAKE_STATIC_LIBRARY_SUFFIX}")
    else()
        set(XGBOOST_LIB_NAME "libxgboost${CMAKE_STATIC_LIBRARY_SUFFIX}")
        set(DMLC_LIB_NAME "libdmlc${CMAKE_STATIC_LIBRARY_SUFFIX}")
    endif()]]
[[    # On Windows, XGBoost installs static libraries without the "lib" prefix.
    if(WIN32)
        set(XGBOOST_LIB_NAME "xgboost${CMAKE_STATIC_LIBRARY_SUFFIX}")
        set(DMLC_LIB_NAME "dmlc${CMAKE_STATIC_LIBRARY_SUFFIX}")
    else()
        set(XGBOOST_LIB_NAME "libxgboost${CMAKE_STATIC_LIBRARY_SUFFIX}")
        set(DMLC_LIB_NAME "libdmlc${CMAKE_STATIC_LIBRARY_SUFFIX}")
    endif()]]
OPENZL_ML_SELECTOR_TEXT "${OPENZL_ML_SELECTOR_TEXT}")

if(OPENZL_ML_SELECTOR_TEXT STREQUAL OPENZL_ML_SELECTOR_ORIGINAL AND
   NOT OPENZL_ML_SELECTOR_TEXT MATCHES "if\\(WIN32\\)")
    message(FATAL_ERROR "Failed to patch OpenZL XGBoost library names")
endif()

file(WRITE "${OPENZL_ML_SELECTOR_CMAKE}" "${OPENZL_ML_SELECTOR_TEXT}")
