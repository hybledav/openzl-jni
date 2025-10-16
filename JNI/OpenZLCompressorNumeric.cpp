#include "OpenZLCompressor.h"
#include "OpenZLNativeSupport.h"
#include "openzl/zl_compress.h"
#include "openzl/zl_data.h"
#include "openzl/zl_decompress.h"
#include <cstdio>
#include <limits>
#include <vector>

namespace {

jbyteArray compressNumericCommon(
        JNIEnv* env,
        NativeState* state,
        const void* data,
        size_t elementSize,
        size_t elementCount)
{
    if (elementCount == 0) {
        return env->NewByteArray(0);
    }
    size_t totalSize = elementSize * elementCount;
    size_t bound = ZL_compressBound(totalSize);
    uint8_t* dstPtr = state->outputScratch.ensure(bound);
    ZL_TypedRef* typedRef = ZL_TypedRef_createNumeric(data, elementSize, elementCount);
    if (typedRef == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Failed to allocate numeric typed reference");
        return nullptr;
    }

    ZL_Report report = ZL_CCtx_compressTypedRef(state->cctx, dstPtr, bound, typedRef);
    ZL_TypedRef_free(typedRef);

    if (ZL_isError(report)) {
        std::fprintf(stderr,
                "ZL_CCtx_compressTypedRef failed: error code %ld\n",
                (long)ZL_RES_code(report));
        const char* context = ZL_CCtx_getErrorContextString(state->cctx, report);
        if (context != nullptr) {
            std::fprintf(stderr, "ZL_CCtx_compressTypedRef context: %s\n", context);
        }
        return nullptr;
    }

    size_t produced = ZL_RES_value(report);
    state->outputScratch.setSize(produced);
    jbyteArray result = env->NewByteArray(static_cast<jsize>(produced));
    if (result != nullptr && produced > 0) {
        env->SetByteArrayRegion(result,
                0,
                static_cast<jsize>(produced),
                reinterpret_cast<jbyte*>(state->outputScratch.ptr()));
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressIntsNative(JNIEnv* env, jobject obj, jintArray data)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "compressInts")) {
        return nullptr;
    }
    if (data == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "data");
        return nullptr;
    }
    jsize length = env->GetArrayLength(data);
    jint* elements = env->GetIntArrayElements(data, nullptr);
    if (elements == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access int array");
        return nullptr;
    }
    jbyteArray result = compressNumericCommon(
            env,
            state,
            elements,
            sizeof(jint),
            static_cast<size_t>(length));
    env->ReleaseIntArrayElements(data, elements, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressLongsNative(JNIEnv* env, jobject obj, jlongArray data)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "compressLongs")) {
        return nullptr;
    }
    if (data == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "data");
        return nullptr;
    }
    jsize length = env->GetArrayLength(data);
    jlong* elements = env->GetLongArrayElements(data, nullptr);
    if (elements == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access long array");
        return nullptr;
    }
    jbyteArray result = compressNumericCommon(
            env,
            state,
            elements,
            sizeof(jlong),
            static_cast<size_t>(length));
    env->ReleaseLongArrayElements(data, elements, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressFloatsNative(JNIEnv* env, jobject obj, jfloatArray data)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "compressFloats")) {
        return nullptr;
    }
    if (data == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "data");
        return nullptr;
    }
    jsize length = env->GetArrayLength(data);
    jfloat* elements = env->GetFloatArrayElements(data, nullptr);
    if (elements == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access float array");
        return nullptr;
    }
    jbyteArray result = compressNumericCommon(
            env,
            state,
            elements,
            sizeof(jfloat),
            static_cast<size_t>(length));
    env->ReleaseFloatArrayElements(data, elements, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressDoublesNative(JNIEnv* env, jobject obj, jdoubleArray data)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "compressDoubles")) {
        return nullptr;
    }
    if (data == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "data");
        return nullptr;
    }
    jsize length = env->GetArrayLength(data);
    jdouble* elements = env->GetDoubleArrayElements(data, nullptr);
    if (elements == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access double array");
        return nullptr;
    }
    jbyteArray result = compressNumericCommon(
            env,
            state,
            elements,
            sizeof(jdouble),
            static_cast<size_t>(length));
    env->ReleaseDoubleArrayElements(data, elements, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressIntsNative(JNIEnv* env, jobject obj, jbyteArray src)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "decompressInts")) {
        return nullptr;
    }
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
    ZL_Report sizeReport = ZL_getDecompressedSize(srcPtr, static_cast<size_t>(len));
    if (ZL_isError(sizeReport)) {
        env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
        return nullptr;
    }
    size_t bufferSize = ZL_RES_value(sizeReport);
    size_t elementCapacity = bufferSize / sizeof(jint);
    if (elementCapacity == 0) {
        elementCapacity = 1;
    }
    std::vector<jint> buffer(elementCapacity);
    ZL_OutputInfo info{};
    ZL_Report report = ZL_DCtx_decompressTyped(state->dctx,
            &info,
            buffer.data(),
            buffer.size() * sizeof(jint),
            srcPtr,
            static_cast<size_t>(len));
    env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
    if (ZL_isError(report)) {
        return nullptr;
    }
    if (info.type != ZL_Type_numeric || info.fixedWidth != sizeof(jint)) {
        throwNew(env, JniRefs().illegalStateException, "Compressed stream is not an int array");
        return nullptr;
    }
    size_t elementCount = info.numElts;
    if (elementCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throwNew(env, JniRefs().illegalStateException, "Decompressed array is too large");
        return nullptr;
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(elementCount));
    if (result != nullptr && elementCount > 0) {
        env->SetIntArrayRegion(result,
                0,
                static_cast<jsize>(elementCount),
                buffer.data());
    }
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressLongsNative(JNIEnv* env, jobject obj, jbyteArray src)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "decompressLongs")) {
        return nullptr;
    }
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
    ZL_Report sizeReport = ZL_getDecompressedSize(srcPtr, static_cast<size_t>(len));
    if (ZL_isError(sizeReport)) {
        env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
        return nullptr;
    }
    size_t bufferSize = ZL_RES_value(sizeReport);
    size_t elementCapacity = bufferSize / sizeof(jlong);
    if (elementCapacity == 0) {
        elementCapacity = 1;
    }
    std::vector<jlong> buffer(elementCapacity);
    ZL_OutputInfo info{};
    ZL_Report report = ZL_DCtx_decompressTyped(state->dctx,
            &info,
            buffer.data(),
            buffer.size() * sizeof(jlong),
            srcPtr,
            static_cast<size_t>(len));
    env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
    if (ZL_isError(report)) {
        return nullptr;
    }
    if (info.type != ZL_Type_numeric || info.fixedWidth != sizeof(jlong)) {
        throwNew(env, JniRefs().illegalStateException, "Compressed stream is not a long array");
        return nullptr;
    }
    size_t elementCount = info.numElts;
    if (elementCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throwNew(env, JniRefs().illegalStateException, "Decompressed array is too large");
        return nullptr;
    }
    jlongArray result = env->NewLongArray(static_cast<jsize>(elementCount));
    if (result != nullptr && elementCount > 0) {
        env->SetLongArrayRegion(result,
                0,
                static_cast<jsize>(elementCount),
                buffer.data());
    }
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressFloatsNative(JNIEnv* env, jobject obj, jbyteArray src)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "decompressFloats")) {
        return nullptr;
    }
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
    ZL_Report sizeReport = ZL_getDecompressedSize(srcPtr, static_cast<size_t>(len));
    if (ZL_isError(sizeReport)) {
        env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
        return nullptr;
    }
    size_t bufferSize = ZL_RES_value(sizeReport);
    size_t elementCapacity = bufferSize / sizeof(jfloat);
    if (elementCapacity == 0) {
        elementCapacity = 1;
    }
    std::vector<jfloat> buffer(elementCapacity);
    ZL_OutputInfo info{};
    ZL_Report report = ZL_DCtx_decompressTyped(state->dctx,
            &info,
            buffer.data(),
            buffer.size() * sizeof(jfloat),
            srcPtr,
            static_cast<size_t>(len));
    env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
    if (ZL_isError(report)) {
        return nullptr;
    }
    if (info.type != ZL_Type_numeric || info.fixedWidth != sizeof(jfloat)) {
        throwNew(env, JniRefs().illegalStateException, "Compressed stream is not a float array");
        return nullptr;
    }
    size_t elementCount = info.numElts;
    if (elementCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throwNew(env, JniRefs().illegalStateException, "Decompressed array is too large");
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(elementCount));
    if (result != nullptr && elementCount > 0) {
        env->SetFloatArrayRegion(result,
                0,
                static_cast<jsize>(elementCount),
                buffer.data());
    }
    return result;
}

extern "C" JNIEXPORT jdoubleArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressDoublesNative(JNIEnv* env, jobject obj, jbyteArray src)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "decompressDoubles")) {
        return nullptr;
    }
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
    ZL_Report sizeReport = ZL_getDecompressedSize(srcPtr, static_cast<size_t>(len));
    if (ZL_isError(sizeReport)) {
        env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
        return nullptr;
    }
    size_t bufferSize = ZL_RES_value(sizeReport);
    size_t elementCapacity = bufferSize / sizeof(jdouble);
    if (elementCapacity == 0) {
        elementCapacity = 1;
    }
    std::vector<jdouble> buffer(elementCapacity);
    ZL_OutputInfo info{};
    ZL_Report report = ZL_DCtx_decompressTyped(state->dctx,
            &info,
            buffer.data(),
            buffer.size() * sizeof(jdouble),
            srcPtr,
            static_cast<size_t>(len));
    env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
    if (ZL_isError(report)) {
        return nullptr;
    }
    if (info.type != ZL_Type_numeric || info.fixedWidth != sizeof(jdouble)) {
        throwNew(env, JniRefs().illegalStateException, "Compressed stream is not a double array");
        return nullptr;
    }
    size_t elementCount = info.numElts;
    if (elementCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throwNew(env, JniRefs().illegalStateException, "Decompressed array is too large");
        return nullptr;
    }
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(elementCount));
    if (result != nullptr && elementCount > 0) {
        env->SetDoubleArrayRegion(result,
                0,
                static_cast<jsize>(elementCount),
                buffer.data());
    }
    return result;
}
