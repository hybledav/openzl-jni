#include "OpenZLCompressor.h"
#include "OpenZLNativeSupport.h"
#include "openzl/zl_compress.h"
#include "openzl/zl_decompress.h"
#include <cstdio>
#include <limits>

extern "C" JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_compressIntoNative(JNIEnv* env,
        jobject obj,
        jbyteArray src,
        jint srcOff,
        jint srcLen,
        jbyteArray dst,
        jint dstOff,
        jint dstLen)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "compressInto")) {
        return -1;
    }
    if (!checkArrayRange(env, src, srcOff, srcLen, "src")) {
        return -1;
    }
    if (!checkArrayRange(env, dst, dstOff, dstLen, "dst")) {
        return -1;
    }

    void* srcPtr = env->GetPrimitiveArrayCritical(src, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Failed to access source array");
        return -1;
    }
    void* dstPtr = env->GetPrimitiveArrayCritical(dst, nullptr);
    if (dstPtr == nullptr) {
        env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
        throwNew(env, JniRefs().outOfMemoryError, "Failed to access destination array");
        return -1;
    }

    auto* srcBytes = static_cast<uint8_t*>(srcPtr) + srcOff;
    auto* dstBytes = static_cast<uint8_t*>(dstPtr) + dstOff;

    ZL_Report result = ZL_CCtx_compress(state->cctx,
            dstBytes,
            static_cast<size_t>(dstLen),
            srcBytes,
            static_cast<size_t>(srcLen));

    env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);

    if (ZL_isError(result)) {
        env->ReleasePrimitiveArrayCritical(dst, dstPtr, JNI_ABORT);
        std::fprintf(stderr, "ZL_CCtx_compress failed: error code %ld\n",
                (long)ZL_RES_code(result));
        const char* context = ZL_CCtx_getErrorContextString(state->cctx, result);
        if (context != nullptr) {
            std::fprintf(stderr, "ZL_CCtx_compress context: %s\n", context);
        }
        return -1;
    }

    env->ReleasePrimitiveArrayCritical(dst, dstPtr, 0);
    return static_cast<jint>(ZL_RES_value(result));
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compress(JNIEnv* env, jobject obj, jbyteArray input)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "compress")) {
        return nullptr;
    }

    if (input == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "input is null");
        return nullptr;
    }

    jsize len = env->GetArrayLength(input);
    void* srcPtr = env->GetPrimitiveArrayCritical(input, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "GetPrimitiveArrayCritical returned null");
        return nullptr;
    }

    size_t bound = ZL_compressBound(static_cast<size_t>(len));
    uint8_t* dstPtr = state->outputScratch.ensure(bound);

    ZL_Report result = ZL_CCtx_compress(state->cctx,
            dstPtr,
            bound,
            srcPtr,
            static_cast<size_t>(len));

    env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);

    if (ZL_isError(result)) {
        std::fprintf(stderr, "ZL_CCtx_compress failed: error code %ld\n",
                (long)ZL_RES_code(result));
        const char* context = ZL_CCtx_getErrorContextString(state->cctx, result);
        if (context != nullptr) {
            std::fprintf(stderr, "ZL_CCtx_compress context: %s\n", context);
        }
        return nullptr;
    }

    size_t compressedSize = ZL_RES_value(result);
    state->outputScratch.setSize(compressedSize);
    jbyteArray jresult = env->NewByteArray(static_cast<jsize>(compressedSize));
    if (compressedSize > 0 && state->outputScratch.ptr() != nullptr) {
        env->SetByteArrayRegion(jresult, 0, static_cast<jsize>(compressedSize),
                reinterpret_cast<jbyte*>(state->outputScratch.ptr()));
    }
    return jresult;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompress(JNIEnv* env, jobject obj, jbyteArray input)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "decompress")) {
        return nullptr;
    }

    if (input == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "input is null");
        return nullptr;
    }

    jsize len = env->GetArrayLength(input);
    void* srcPtr = env->GetPrimitiveArrayCritical(input, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "GetPrimitiveArrayCritical returned null");
        return nullptr;
    }

    ZL_Report sizeReport = ZL_getDecompressedSize(srcPtr,
            static_cast<size_t>(len));
    if (ZL_isError(sizeReport)) {
        std::fprintf(stderr,
                "ZL_getDecompressedSize failed: error code %ld, input size %ld\n",
                (long)ZL_RES_code(sizeReport),
                (long)len);
        env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);
        return nullptr;
    }

    size_t outCap = ZL_RES_value(sizeReport);
    uint8_t* dstPtr = state->outputScratch.ensure(outCap);

    ZL_Report result = ZL_DCtx_decompress(state->dctx,
            dstPtr,
            outCap,
            srcPtr,
            static_cast<size_t>(len));

    env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);

    if (ZL_isError(result)) {
        std::fprintf(stderr,
                "ZL_DCtx_decompress failed: error code %ld, input size %ld, output buffer size %zu\n",
                (long)ZL_RES_code(result),
                (long)len,
                outCap);
        return nullptr;
    }

    size_t decompressedSize = ZL_RES_value(result);
    state->outputScratch.setSize(decompressedSize);
    jbyteArray jresult = env->NewByteArray(static_cast<jsize>(decompressedSize));
    if (decompressedSize > 0 && state->outputScratch.ptr() != nullptr) {
        env->SetByteArrayRegion(jresult, 0, static_cast<jsize>(decompressedSize),
                reinterpret_cast<jbyte*>(state->outputScratch.ptr()));
    }
    return jresult;
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressIntoNative(JNIEnv* env,
        jobject obj,
        jbyteArray src,
        jint srcOff,
        jint srcLen,
        jbyteArray dst,
        jint dstOff,
        jint dstLen)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "decompressInto")) {
        return -1;
    }
    if (!checkArrayRange(env, src, srcOff, srcLen, "src")) {
        return -1;
    }
    if (!checkArrayRange(env, dst, dstOff, dstLen, "dst")) {
        return -1;
    }

    void* srcPtr = env->GetPrimitiveArrayCritical(src, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Failed to access source array");
        return -1;
    }
    void* dstPtr = env->GetPrimitiveArrayCritical(dst, nullptr);
    if (dstPtr == nullptr) {
        env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
        throwNew(env, JniRefs().outOfMemoryError, "Failed to access destination array");
        return -1;
    }

    auto* srcBytes = static_cast<uint8_t*>(srcPtr) + srcOff;
    auto* dstBytes = static_cast<uint8_t*>(dstPtr) + dstOff;

    ZL_Report result = ZL_DCtx_decompress(state->dctx,
            dstBytes,
            static_cast<size_t>(dstLen),
            srcBytes,
            static_cast<size_t>(srcLen));

    env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);

    if (ZL_isError(result)) {
        env->ReleasePrimitiveArrayCritical(dst, dstPtr, JNI_ABORT);
        std::fprintf(stderr,
                "ZL_DCtx_decompress failed: error code %ld, input size %ld, output buffer size %ld\n",
                (long)ZL_RES_code(result),
                (long)srcLen,
                (long)dstLen);
        return -1;
    }

    env->ReleasePrimitiveArrayCritical(dst, dstPtr, 0);
    return static_cast<jint>(ZL_RES_value(result));
}

extern "C" JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_getDecompressedSizeNative(JNIEnv* env,
        jobject obj,
        jbyteArray input)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "getDecompressedSize")) {
        return -1;
    }

    if (input == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "input is null");
        return -1;
    }

    jsize len = env->GetArrayLength(input);
    void* ptr = env->GetPrimitiveArrayCritical(input, nullptr);
    if (ptr == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "GetPrimitiveArrayCritical returned null");
        return -1;
    }

    ZL_Report sizeReport = ZL_getDecompressedSize(ptr, static_cast<size_t>(len));
    env->ReleasePrimitiveArrayCritical(input, ptr, JNI_ABORT);
    if (ZL_isError(sizeReport)) {
        return -1;
    }
    return static_cast<jlong>(ZL_RES_value(sizeReport));
}
