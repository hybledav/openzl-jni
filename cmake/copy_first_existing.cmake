if(EXISTS "${OUTPUT_FILE}")
    return()
endif()

foreach(CANDIDATE IN LISTS CANDIDATES)
    if(EXISTS "${CANDIDATE}")
        file(COPY_FILE "${CANDIDATE}" "${OUTPUT_FILE}")
        return()
    endif()
endforeach()

message(FATAL_ERROR "None of the candidate libraries exist: ${CANDIDATES}")
