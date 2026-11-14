# Keep a trained compressor's target format effective in Protobuf conversion.
set(PROTOBUF_SERIALIZER_HEADER "${openzl_SOURCE_DIR}/tools/protobuf/ProtoSerializer.h")
file(READ "${PROTOBUF_SERIALIZER_HEADER}" PROTOBUF_SERIALIZER_SOURCE)
set(PROTOBUF_FORMAT_OVERRIDE "cctx_.setParameter(CParam::FormatVersion, ZL_MAX_FORMAT_VERSION);")
set(PROTOBUF_FORMAT_MARKER "// openzl-jni: inherit the compressor's target format.")
string(FIND "${PROTOBUF_SERIALIZER_SOURCE}" "${PROTOBUF_FORMAT_OVERRIDE}" PROTOBUF_OVERRIDE_POSITION)
if(NOT PROTOBUF_OVERRIDE_POSITION EQUAL -1)
    string(REPLACE "${PROTOBUF_FORMAT_OVERRIDE}" "${PROTOBUF_FORMAT_MARKER}"
        PROTOBUF_SERIALIZER_SOURCE "${PROTOBUF_SERIALIZER_SOURCE}")
    file(WRITE "${PROTOBUF_SERIALIZER_HEADER}" "${PROTOBUF_SERIALIZER_SOURCE}")
else()
    string(FIND "${PROTOBUF_SERIALIZER_SOURCE}" "${PROTOBUF_FORMAT_MARKER}" PROTOBUF_MARKER_POSITION)
    if(PROTOBUF_MARKER_POSITION EQUAL -1)
        message(FATAL_ERROR "Review the OpenZL Protobuf context patch for the selected upstream version")
    endif()
endif()

# Expose the decoder context's existing dictionary-loader attachment to JNI.
set(PROTOBUF_DESERIALIZER_HEADER "${openzl_SOURCE_DIR}/tools/protobuf/ProtoDeserializer.h")
file(READ "${PROTOBUF_DESERIALIZER_HEADER}" PROTOBUF_DESERIALIZER_SOURCE)
set(PROTOBUF_LOADER_MARKER "// openzl-jni: allow dictionary-backed Protobuf decoding.")
string(FIND "${PROTOBUF_DESERIALIZER_SOURCE}" "${PROTOBUF_LOADER_MARKER}" PROTOBUF_LOADER_POSITION)
if(PROTOBUF_LOADER_POSITION EQUAL -1)
    set(PROTOBUF_DECODER_ANCHOR "    ProtoDeserializer()          = default;")
    string(FIND "${PROTOBUF_DESERIALIZER_SOURCE}" "${PROTOBUF_DECODER_ANCHOR}" PROTOBUF_DECODER_POSITION)
    if(PROTOBUF_DECODER_POSITION EQUAL -1)
        message(FATAL_ERROR "Review the OpenZL Protobuf dictionary-loader adapter")
    endif()
    string(REPLACE "${PROTOBUF_DECODER_ANCHOR}"
        "${PROTOBUF_DECODER_ANCHOR}\n\n    ${PROTOBUF_LOADER_MARKER}\n    void refDictLoader(ZL_DictLoader* loader)\n    {\n        ZL_DCtx_refDictLoader(dctx_.get(), loader);\n    }"
        PROTOBUF_DESERIALIZER_SOURCE "${PROTOBUF_DESERIALIZER_SOURCE}")
    file(WRITE "${PROTOBUF_DESERIALIZER_HEADER}" "${PROTOBUF_DESERIALIZER_SOURCE}")
endif()
