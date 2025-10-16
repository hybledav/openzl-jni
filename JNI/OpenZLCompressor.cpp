#include "OpenZLCompressor.h"
#include "OpenZLNativeSupport.h"
#include "openzl/cpp/CParam.hpp"
#include "openzl/cpp/Compressor.hpp"
#include "openzl/codecs/zl_bitpack.h"
#include "openzl/codecs/zl_constant.h"
#include "openzl/codecs/zl_entropy.h"
#include "openzl/codecs/zl_generic.h"
#include "openzl/codecs/zl_store.h"
#include "openzl/codecs/zl_zstd.h"
#include "openzl/codecs/zl_sddl.h"
#include "openzl/zl_compress.h"
#include "openzl/zl_compressor.h"
#include "openzl/zl_data.h"
#include "openzl/zl_decompress.h"
#include "openzl/zl_opaque_types.h"
#include "custom_parsers/sddl/sddl_profile.h"
#include "tools/sddl/compiler/Compiler.h"
#include "tools/sddl/compiler/Exception.h"
#include <array>
#include <limits>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>

static jint inferGraphOrdinal(ZL_Type outputType, size_t compressedSize, size_t decompressedSize)
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

static jlongArray describeFrameInternal(JNIEnv* env, const uint8_t* data, size_t length)
{
    if (data == nullptr || length == 0) {
        throwNew(env, JniRefs().illegalArgumentException, "Compressed payload is empty");
        return nullptr;
    }

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

extern "C" JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_nativeCreate(JNIEnv*, jobject, jint graphOrdinal)
{
    try {
        ZL_GraphID graph = graphIdFromOrdinal(graphOrdinal);
        NativeState* state = acquireState(graph);
        return reinterpret_cast<jlong>(state);
    } catch (const std::exception& e) {
        fprintf(stderr, "Failed to initialize NativeState: %s\n", e.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_setParameter(JNIEnv* env, jobject obj, jint param, jint value)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "setParameter")) {
        return;
    }
    state->compressor.setParameter(static_cast<openzl::CParam>(param), value);
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_getParameter(JNIEnv* env, jobject obj, jint param)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "getParameter")) {
        return 0;
    }
    return state->compressor.getParameter(static_cast<openzl::CParam>(param));
}

extern "C" JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLCompressor_serialize(JNIEnv* env, jobject obj)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "serialize")) {
        return env->NewStringUTF("");
    }
    std::string result = state->compressor.serialize();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLCompressor_serializeToJson(JNIEnv* env, jobject obj)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "serializeToJson")) {
        return env->NewStringUTF("");
    }
    std::string result = state->compressor.serializeToJson();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_maxCompressedSizeNative(JNIEnv* env,
        jclass,
        jint inputSize)
{
    if (inputSize < 0) {
        throwNew(env, JniRefs().illegalArgumentException, "inputSize must be non-negative");
        return -1;
    }

    size_t bound = ZL_compressBound(static_cast<size_t>(inputSize));
    if (bound > static_cast<size_t>(std::numeric_limits<jlong>::max())) {
        throwNew(env, JniRefs().illegalStateException, "Compression bound exceeds jlong capacity");
        return -1;
    }
    return static_cast<jlong>(bound);
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_resetNative(JNIEnv* env, jobject obj)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "reset")) {
        return;
    }
    state->reset();
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_destroyCompressor(JNIEnv* env, jobject obj)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "destroy")) {
        return;
    }
    recycleState(state);
    setNativeHandle(env, obj, nullptr);
}

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
        fprintf(stderr, "ZL_CCtx_compress failed: error code %ld\n",
                (long)ZL_RES_code(result));
        const char* context = ZL_CCtx_getErrorContextString(state->cctx, result);
        if (context != nullptr) {
            fprintf(stderr, "ZL_CCtx_compress context: %s\n", context);
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
        fprintf(stderr, "ZL_CCtx_compress failed: error code %ld\n",
                (long)ZL_RES_code(result));
        const char* context = ZL_CCtx_getErrorContextString(state->cctx, result);
        if (context != nullptr) {
            fprintf(stderr, "ZL_CCtx_compress context: %s\n", context);
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
        fprintf(stderr,
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
        fprintf(stderr,
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
    return describeFrameInternal(env,
            base + position,
            static_cast<size_t>(length));
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
        fprintf(stderr,
                "ZL_DCtx_decompress failed: error code %ld, input size %ld, output buffer size %ld\n",
                (long)ZL_RES_code(result),
                (long)srcLen,
                (long)dstLen);
        return -1;
    }

    env->ReleasePrimitiveArrayCritical(dst, dstPtr, 0);
    return static_cast<jint>(ZL_RES_value(result));
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

    ZL_Report result = ZL_CCtx_compress(state->cctx,
            dstPtr,
            static_cast<size_t>(dstLen),
            srcPtr,
            static_cast<size_t>(srcLen));
    if (ZL_isError(result)) {
        fprintf(stderr, "ZL_CCtx_compress failed: error code %ld\n",
                (long)ZL_RES_code(result));
        const char* context = ZL_CCtx_getErrorContextString(state->cctx, result);
        if (context != nullptr) {
            fprintf(stderr, "ZL_CCtx_compress context: %s\n", context);
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

    ZL_Report result = ZL_DCtx_decompress(state->dctx,
            dstPtr,
            static_cast<size_t>(dstLen),
            srcPtr,
            static_cast<size_t>(srcLen));
    if (ZL_isError(result)) {
        fprintf(stderr,
                "ZL_DCtx_decompress failed: error code %ld, input size %ld, output buffer size %ld\n",
                (long)ZL_RES_code(result),
                (long)srcLen,
                (long)dstLen);
        return -1;
    }
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

    srcPtr += srcPos;
    ZL_Report sizeReport = ZL_getDecompressedSize(srcPtr, static_cast<size_t>(srcLen));
    if (ZL_isError(sizeReport)) {
        return -1;
    }
    return static_cast<jlong>(ZL_RES_value(sizeReport));
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_configureSddlNative(JNIEnv* env,
        jobject obj,
        jbyteArray compiledDescription)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "configureSddl")) {
        return;
    }
    if (compiledDescription == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "compiledDescription");
        return;
    }

    jsize length = env->GetArrayLength(compiledDescription);
    if (length <= 0) {
        throwIllegalArgument(env, "Compiled SDDL description must not be empty");
        return;
    }

    void* bytes = env->GetPrimitiveArrayCritical(compiledDescription, nullptr);
    if (bytes == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access compiled description");
        return;
    }

    auto result = ZL_SDDL_setupProfile(
            state->compressor.get(), bytes, static_cast<size_t>(length));

    env->ReleasePrimitiveArrayCritical(compiledDescription, bytes, JNI_ABORT);

    if (ZL_RES_isError(result)) {
        auto context = state->compressor.getErrorContextString(result);
        std::string message = "Failed to configure SDDL profile";
        if (!context.empty()) {
            message.append(": ").append(context.data(), context.size());
        }
        throwIllegalState(env, message);
        return;
    }

    state->setGraph(ZL_RES_value(result));
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLSddl_compileNative(JNIEnv* env,
        jclass,
        jstring source,
        jboolean includeDebugInfo,
        jint verbosity)
{
    if (source == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "description");
        return nullptr;
    }

    const char* sourceChars = env->GetStringUTFChars(source, nullptr);
    if (sourceChars == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to read source");
        return nullptr;
    }

    std::string compiled;
    try {
        openzl::sddl::Compiler::Options options;
        if (!includeDebugInfo) {
            options.with_no_debug_info();
        }
        if (verbosity != 0) {
            options.with_verbosity(static_cast<int>(verbosity));
        }
        openzl::sddl::Compiler compiler{ std::move(options) };
        compiled = compiler.compile(sourceChars, "<jni>");
    } catch (const openzl::sddl::CompilerException& ex) {
        env->ReleaseStringUTFChars(source, sourceChars);
        throwIllegalArgument(env, ex.what());
        return nullptr;
    } catch (const std::exception& ex) {
        env->ReleaseStringUTFChars(source, sourceChars);
        throwIllegalState(env, ex.what());
        return nullptr;
    }

    env->ReleaseStringUTFChars(source, sourceChars);

    if (compiled.empty()) {
        throwIllegalState(env, "Compiler returned empty result");
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(compiled.size()));
    if (result == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to allocate compiled output");
        return nullptr;
    }

    env->SetByteArrayRegion(result,
            0,
            static_cast<jsize>(compiled.size()),
            reinterpret_cast<const jbyte*>(compiled.data()));
    return result;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*)
{
    void* envVoid = nullptr;
    if (vm->GetEnv(&envVoid, JNI_VERSION_1_8) != JNI_OK) {
        return JNI_ERR;
    }
    JNIEnv* env = static_cast<JNIEnv*>(envVoid);
    if (!initJniRefs(env)) {
        clearJniRefs(env);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return JNI_ERR;
    }
    return JNI_VERSION_1_8;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*)
{
    void* envVoid = nullptr;
    if (vm->GetEnv(&envVoid, JNI_VERSION_1_8) != JNI_OK) {
        return;
    }
    JNIEnv* env = static_cast<JNIEnv*>(envVoid);
    clearJniRefs(env);
}
