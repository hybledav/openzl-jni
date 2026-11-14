#include "OpenZLCompressor.h"
#include "OpenZLNativeSupport.h"
#include "openzl/zl_compress.h"
#include "openzl/zl_decompress.h"
#include <cstdio>
#include <cstdint>

namespace {
bool overlaps(const void* source, size_t sourceSize, const void* destination, size_t destinationSize)
{
    const auto src = reinterpret_cast<uintptr_t>(source);
    const auto dst = reinterpret_cast<uintptr_t>(destination);
    return sourceSize > 0 && destinationSize > 0 && src < dst + destinationSize && dst < src + sourceSize;
}
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_compressDirect(JNIEnv* env,
        jobject obj,
        jobject src,
        jint srcPos,
        jint srcLen,
        jobject dst,
        jint dstPos,
        jint dstLen)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "compress")) {
        return -1;
    }
    state->compressionWarningsVisible = true;

    if (!ensureDirectRange(env, src, srcPos, srcLen, "src")) {
        return -1;
    }
    if (!ensureDirectRange(env, dst, dstPos, dstLen, "dst")) {
        return -1;
    }

    auto* srcPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(src));
    auto* dstPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!srcPtr || !dstPtr) {
        return -1;
    }

    srcPtr += srcPos;
    dstPtr += dstPos;
    if (overlaps(srcPtr, static_cast<size_t>(srcLen), dstPtr, static_cast<size_t>(dstLen))) {
        throwIllegalArgument(env, "Input and output ranges must not overlap");
        return -1;
    }

    ZL_Report result = ZL_CCtx_compress(state->cctx,
            dstPtr,
            static_cast<size_t>(dstLen),
            srcPtr,
            static_cast<size_t>(srcLen));
    if (ZL_isError(result)) {
        std::fprintf(stderr, "ZL_CCtx_compress failed: error code %ld\n",
                (long)ZL_RES_code(result));
        const char* context = ZL_CCtx_getErrorContextString(state->cctx, result);
        if (context != nullptr) {
            std::fprintf(stderr, "ZL_CCtx_compress context: %s\n", context);
        }
        return -1;
    }
    return static_cast<jint>(ZL_RES_value(result));
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressDirect(JNIEnv* env,
        jobject obj,
        jobject src,
        jint srcPos,
        jint srcLen,
        jobject dst,
        jint dstPos,
        jint dstLen)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "decompress")) {
        return -1;
    }
    state->decompressionWarningsVisible = true;

    if (!ensureDirectRange(env, src, srcPos, srcLen, "src")) {
        return -1;
    }
    if (!ensureDirectRange(env, dst, dstPos, dstLen, "dst")) {
        return -1;
    }

    auto* srcPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(src));
    auto* dstPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!srcPtr || !dstPtr) {
        return -1;
    }

    srcPtr += srcPos;
    dstPtr += dstPos;
    if (overlaps(srcPtr, static_cast<size_t>(srcLen), dstPtr, static_cast<size_t>(dstLen))) {
        throwIllegalArgument(env, "Input and output ranges must not overlap");
        return -1;
    }

    ZL_Report result = ZL_DCtx_decompress(state->dctx,
            dstPtr,
            static_cast<size_t>(dstLen),
            srcPtr,
            static_cast<size_t>(srcLen));
    if (ZL_isError(result)) {
        std::fprintf(stderr,
                "ZL_DCtx_decompress failed: error code %ld, input size %ld, output buffer size %ld\n",
                (long)ZL_RES_code(result),
                (long)srcLen,
                (long)dstLen);
        return -1;
    }
    return static_cast<jint>(ZL_RES_value(result));
}

extern "C" JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_getDecompressedSizeDirect(JNIEnv* env,
        jobject obj,
        jobject src,
        jint srcPos,
        jint srcLen)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "getDecompressedSize")) {
        return -1;
    }

    if (!ensureDirectRange(env, src, srcPos, srcLen, "src")) {
        return -1;
    }

    auto* srcPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(src));
    if (!srcPtr) {
        return -1;
    }

    srcPtr += srcPos;
    ZL_Report sizeReport = ZL_getDecompressedSize(srcPtr, static_cast<size_t>(srcLen));
    if (ZL_isError(sizeReport)) {
        return -1;
    }
    return static_cast<jlong>(ZL_RES_value(sizeReport));
}
