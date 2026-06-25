if(EXISTS "${OUTPUT_FILE}")
    return()
endif()

set(CANDIDATES
    "${LIB_DIR}/${LIB_NAME}.lib"
    "${LIB_DIR}/lib${LIB_NAME}.lib"
    "${LIB_DIR}/${LIB_NAME}.a"
    "${LIB_DIR}/lib${LIB_NAME}.a"
)

foreach(CANDIDATE IN LISTS CANDIDATES)
    if(EXISTS "${CANDIDATE}")
        file(COPY_FILE "${CANDIDATE}" "${OUTPUT_FILE}")
        return()
    endif()
endforeach()

message(FATAL_ERROR "None of the candidate libraries exist: ${CANDIDATES}")
