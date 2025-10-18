#include "OpenZLCompressor.h"
#include "OpenZLNativeSupport.h"
#include "openzl/zl_data.h"
#include "openzl/zl_decompress.h"
#include "openzl/zl_opaque_types.h"
#include <array>

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

jlongArray describeFrameInternal(JNIEnv* env, const uint8_t* data, size_t length)
{
    ZL_FrameInfo* frameInfo = ZL_FrameInfo_create(data, length);
    if (frameInfo == nullptr) {
        throwNew(env, JniRefs().illegalStateException, "Failed to create frame info");
        return nullptr;
    }

    ZL_Report formatReport = ZL_FrameInfo_getFormatVersion(frameInfo);
    if (ZL_isError(formatReport)) {
        ZL_FrameInfo_free(frameInfo);
        throwNew(env, JniRefs().illegalStateException, "Unable to read frame format version");
        return nullptr;
    }

    ZL_Report outputsReport = ZL_FrameInfo_getNumOutputs(frameInfo);
    if (ZL_isError(outputsReport)) {
        ZL_FrameInfo_free(frameInfo);
        throwNew(env, JniRefs().illegalStateException, "Unable to read frame outputs");
        return nullptr;
    }

    size_t numOutputs = ZL_RES_value(outputsReport);
    if (numOutputs == 0) {
        ZL_FrameInfo_free(frameInfo);
        throwNew(env, JniRefs().illegalStateException, "Frame does not expose outputs");
        return nullptr;
    }

    ZL_Report decompressedReport = ZL_FrameInfo_getDecompressedSize(frameInfo, 0);
    if (ZL_isError(decompressedReport)) {
        ZL_FrameInfo_free(frameInfo);
        throwNew(env, JniRefs().illegalStateException, "Unable to determine decompressed size");
        return nullptr;
    }
    size_t decompressedSize = ZL_RES_value(decompressedReport);

    ZL_Report typeReport = ZL_FrameInfo_getOutputType(frameInfo, 0);
    if (ZL_isError(typeReport)) {
        ZL_FrameInfo_free(frameInfo);
        throwNew(env, JniRefs().illegalStateException, "Unable to determine output type");
        return nullptr;
    }
    int outputType = static_cast<int>(ZL_RES_value(typeReport));

    long elementCount = -1;
    ZL_Report elementsReport = ZL_FrameInfo_getNumElts(frameInfo, 0);
    if (!ZL_isError(elementsReport)) {
        elementCount = static_cast<long>(ZL_RES_value(elementsReport));
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

    ZL_FrameInfo_free(frameInfo);

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
    jlongArray result = describeFrameInternal(env,
            static_cast<const uint8_t*>(srcPtr),
            static_cast<size_t>(len));
    env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
    return result;
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
    return describeFrameInternal(env,
            base + position,
            static_cast<size_t>(length));
}
