#include "OpenZLCompressor.h"
#include "OpenZLNativeSupport.h"
#include "openzl/zl_data.h"
#include "openzl/zl_decompress.h"
#include "openzl/zl_opaque_types.h"
#include <array>
#include <memory>
#include <stdexcept>

namespace {

jint inferGraphOrdinal(ZL_Type outputType, size_t compressedSize, size_t decompressedSize)
{
    if (compressedSize == decompressedSize && decompressedSize > 0) {
        return 3; // STORE graph keeps data verbatim
    }
    switch (outputType) {
    case ZL_Type_numeric:
        return 2;
    case ZL_Type_struct:
        return 1;
    case ZL_Type_string:
        return 7;
    case ZL_Type_serial:
    default:
        return 0;
    }
}

std::array<jlong, 6> describeFrameInternal(const uint8_t* data, size_t length)
{
    std::unique_ptr<ZL_FrameInfo, decltype(&ZL_FrameInfo_free)> owned(
            ZL_FrameInfo_create(data, length), ZL_FrameInfo_free);
    auto* frameInfo = owned.get();
    if (frameInfo == nullptr) {
        throw std::runtime_error("Failed to create frame info");
    }

    ZL_Report formatReport = ZL_FrameInfo_getFormatVersion(frameInfo);
    if (ZL_isError(formatReport)) {
        throw std::runtime_error("Unable to read frame format version");
    }

    ZL_Report outputsReport = ZL_FrameInfo_getNumOutputs(frameInfo);
    if (ZL_isError(outputsReport)) {
        throw std::runtime_error("Unable to read frame outputs");
    }

    size_t numOutputs = ZL_RES_value(outputsReport);
    if (numOutputs == 0) {
        throw std::runtime_error("Frame does not expose outputs");
    }

    ZL_Report decompressedReport = ZL_FrameInfo_getDecompressedSize(frameInfo, 0);
    if (ZL_isError(decompressedReport)) {
        throw std::runtime_error("Unable to determine decompressed size");
    }
    size_t decompressedSize = ZL_RES_value(decompressedReport);

    ZL_Report typeReport = ZL_FrameInfo_getOutputType(frameInfo, 0);
    if (ZL_isError(typeReport)) {
        throw std::runtime_error("Unable to determine output type");
    }
    int outputType = static_cast<int>(ZL_RES_value(typeReport));

    jlong elementCount = -1;
    ZL_Report elementsReport = ZL_FrameInfo_getNumElts(frameInfo, 0);
    if (!ZL_isError(elementsReport)) {
        elementCount = static_cast<jlong>(ZL_RES_value(elementsReport));
    }

    jint graphOrdinal = inferGraphOrdinal(static_cast<ZL_Type>(outputType), length, decompressedSize);

    std::array<jlong, 6> meta = {
        static_cast<jlong>(decompressedSize),
        static_cast<jlong>(length),
        static_cast<jlong>(outputType),
        static_cast<jlong>(graphOrdinal),
        static_cast<jlong>(elementCount),
        static_cast<jlong>(ZL_RES_value(formatReport)),
    };

    return meta;
}

jlongArray toJavaMetadata(JNIEnv* env, const std::array<jlong, 6>& meta)
{
    jlongArray result = env->NewLongArray(static_cast<jsize>(meta.size()));
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(meta.size()), meta.data());
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL Java_io_github_hybledav_OpenZLCompressor_describeFrameNative(JNIEnv* env, jobject, jbyteArray src)
{
    if (src == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "compressed");
        return nullptr;
    }
    jsize len = env->GetArrayLength(src);
    void* srcPtr = env->GetPrimitiveArrayCritical(src, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access compressed payload");
        return nullptr;
    }
    std::array<jlong, 6> meta;
    try {
        meta = describeFrameInternal(static_cast<const uint8_t*>(srcPtr), static_cast<size_t>(len));
    } catch (const std::exception& ex) {
        env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
        throwIllegalState(env, ex.what());
        return nullptr;
    }
    env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
    return toJavaMetadata(env, meta);
}

extern "C" JNIEXPORT jlongArray JNICALL Java_io_github_hybledav_OpenZLCompressor_describeFrameDirectNative(JNIEnv* env, jobject, jobject buffer, jint position, jint length)
{
    if (!ensureDirectRange(env, buffer, position, length, "compressed")) {
        return nullptr;
    }
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!base) {
        return nullptr;
    }
    try {
        return toJavaMetadata(env, describeFrameInternal(base + position, static_cast<size_t>(length)));
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}
