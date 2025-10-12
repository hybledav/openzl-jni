#include "OpenZLCompressor.h"
#include "openzl/cpp/CParam.hpp"
#include "openzl/cpp/Compressor.hpp"
#include "openzl/zl_compress.h"
#include "openzl/zl_decompress.h"
#include "openzl/zl_opaque_types.h"
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct CachedJNIRefs {
    jclass compressorClass = nullptr;
    jfieldID nativeHandleField = nullptr;
    jclass nullPointerException = nullptr;
    jclass illegalArgumentException = nullptr;
    jclass illegalStateException = nullptr;
    jclass outOfMemoryError = nullptr;
};

static CachedJNIRefs gJNIRefs;

struct NativeState {
    openzl::Compressor compressor;
    ZL_CCtx* cctx = nullptr;
    ZL_DCtx* dctx = nullptr;
    std::vector<uint8_t> inputScratch;
    std::vector<uint8_t> outputScratch;

    static void expectSuccess(ZL_Report report, const char* action)
    {
        if (ZL_isError(report)) {
            throw std::runtime_error(std::string(action)
                    + " failed: error code "
                    + std::to_string(static_cast<long>(ZL_RES_code(report))));
        }
    }

    void applyDefaultParameters()
    {
        expectSuccess(
                ZL_CCtx_setParameter(cctx, ZL_CParam_stickyParameters, 1),
                "ZL_CCtx_setParameter(stickyParameters)");
        expectSuccess(
                ZL_CCtx_setParameter(
                        cctx, ZL_CParam_compressionLevel, ZL_COMPRESSIONLEVEL_DEFAULT),
                "ZL_CCtx_setParameter(compressionLevel)");
        expectSuccess(
                ZL_CCtx_setParameter(
                        cctx, ZL_CParam_formatVersion, ZL_getDefaultEncodingVersion()),
                "ZL_CCtx_setParameter(formatVersion)");
        expectSuccess(
                ZL_DCtx_setParameter(dctx, ZL_DParam_stickyParameters, 1),
                "ZL_DCtx_setParameter(stickyParameters)");
    }

    NativeState()
    {
        cctx = ZL_CCtx_create();
        dctx = ZL_DCtx_create();
        if (!cctx || !dctx) {
            throw std::bad_alloc();
        }
        applyDefaultParameters();
    }

    ~NativeState()
    {
        if (cctx) {
            ZL_CCtx_free(cctx);
        }
        if (dctx) {
            ZL_DCtx_free(dctx);
        }
    }

    void reset()
    {
        expectSuccess(ZL_CCtx_resetParameters(cctx), "ZL_CCtx_resetParameters");
        expectSuccess(ZL_DCtx_resetParameters(dctx), "ZL_DCtx_resetParameters");
        applyDefaultParameters();
        inputScratch.clear();
        outputScratch.clear();
    }
};

static jclass makeGlobalClassRef(JNIEnv* env, const char* name)
{
    jclass local = env->FindClass(name);
    if (!local) {
        return nullptr;
    }
    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return global;
}

static bool initJniRefs(JNIEnv* env)
{
    gJNIRefs.compressorClass = makeGlobalClassRef(env, "io/github/hybledav/OpenZLCompressor");
    if (!gJNIRefs.compressorClass) {
        return false;
    }
    gJNIRefs.nativeHandleField = env->GetFieldID(gJNIRefs.compressorClass, "nativeHandle", "J");
    if (!gJNIRefs.nativeHandleField) {
        return false;
    }
    gJNIRefs.nullPointerException = makeGlobalClassRef(env, "java/lang/NullPointerException");
    gJNIRefs.illegalArgumentException = makeGlobalClassRef(env, "java/lang/IllegalArgumentException");
    gJNIRefs.illegalStateException = makeGlobalClassRef(env, "java/lang/IllegalStateException");
    gJNIRefs.outOfMemoryError = makeGlobalClassRef(env, "java/lang/OutOfMemoryError");
    if (!gJNIRefs.nullPointerException || !gJNIRefs.illegalArgumentException
            || !gJNIRefs.illegalStateException || !gJNIRefs.outOfMemoryError) {
        return false;
    }
    return true;
}

static void clearJniRefs(JNIEnv* env)
{
    if (gJNIRefs.compressorClass) {
        env->DeleteGlobalRef(gJNIRefs.compressorClass);
        gJNIRefs.compressorClass = nullptr;
    }
    if (gJNIRefs.nullPointerException) {
        env->DeleteGlobalRef(gJNIRefs.nullPointerException);
        gJNIRefs.nullPointerException = nullptr;
    }
    if (gJNIRefs.illegalArgumentException) {
        env->DeleteGlobalRef(gJNIRefs.illegalArgumentException);
        gJNIRefs.illegalArgumentException = nullptr;
    }
    if (gJNIRefs.illegalStateException) {
        env->DeleteGlobalRef(gJNIRefs.illegalStateException);
        gJNIRefs.illegalStateException = nullptr;
    }
    if (gJNIRefs.outOfMemoryError) {
        env->DeleteGlobalRef(gJNIRefs.outOfMemoryError);
        gJNIRefs.outOfMemoryError = nullptr;
    }
    gJNIRefs.nativeHandleField = nullptr;
}

thread_local NativeState* tlsCachedState = nullptr;

static NativeState* getState(JNIEnv* env, jobject obj)
{
    return reinterpret_cast<NativeState*>(
            env->GetLongField(obj, gJNIRefs.nativeHandleField));
}

static void setNativeHandle(JNIEnv* env, jobject obj, NativeState* value)
{
    env->SetLongField(obj, gJNIRefs.nativeHandleField, reinterpret_cast<jlong>(value));
}

static void throwNew(JNIEnv* env, jclass clazz, const char* message)
{
    env->ThrowNew(clazz, message);
}

static bool ensureState(NativeState* state, const char* method)
{
    if (state != nullptr) {
        return true;
    }
    fprintf(stderr, "OpenZLCompressor.%s called after close()\n", method);
    return false;
}

static bool ensureDirect(JNIEnv* env, jobject buffer, const char* name)
{
    if (buffer == nullptr) {
        throwNew(env, gJNIRefs.nullPointerException, name);
        return false;
    }
    void* addr = env->GetDirectBufferAddress(buffer);
    if (addr == nullptr) {
        throwNew(env, gJNIRefs.illegalArgumentException, "ByteBuffer must be direct");
        return false;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_createCompressor(JNIEnv*, jobject)
{
    try {
        NativeState* state = nullptr;
        if (tlsCachedState != nullptr) {
            state = tlsCachedState;
            tlsCachedState = nullptr;
            state->reset();
        } else {
            state = new NativeState();
        }
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
        throwNew(env, gJNIRefs.illegalArgumentException, "inputSize must be non-negative");
        return -1;
    }

    size_t bound = ZL_compressBound(static_cast<size_t>(inputSize));
    if (bound > static_cast<size_t>(std::numeric_limits<jlong>::max())) {
        throwNew(env, gJNIRefs.illegalStateException, "Compression bound exceeds jlong capacity");
        return -1;
    }
    return static_cast<jlong>(bound);
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_destroyCompressor(JNIEnv* env, jobject obj)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "destroy")) {
        return;
    }
    state->reset();
    if (tlsCachedState == nullptr) {
        tlsCachedState = state;
    } else {
        delete state;
    }
    setNativeHandle(env, obj, nullptr);
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compress(JNIEnv* env, jobject obj, jbyteArray input)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "compress")) {
        return nullptr;
    }

    if (input == nullptr) {
        throwNew(env, gJNIRefs.nullPointerException, "input is null");
        return nullptr;
    }

    jsize len = env->GetArrayLength(input);
    void* srcPtr = env->GetPrimitiveArrayCritical(input, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "GetPrimitiveArrayCritical returned null");
        return nullptr;
    }

    size_t bound = ZL_compressBound(static_cast<size_t>(len));
    state->outputScratch.resize(bound);

    ZL_Report result = ZL_CCtx_compress(state->cctx,
            state->outputScratch.data(),
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
    jbyteArray jresult = env->NewByteArray(static_cast<jsize>(compressedSize));
    env->SetByteArrayRegion(jresult, 0, static_cast<jsize>(compressedSize),
            reinterpret_cast<jbyte*>(state->outputScratch.data()));
    return jresult;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompress(JNIEnv* env, jobject obj, jbyteArray input)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "decompress")) {
        return nullptr;
    }

    if (input == nullptr) {
        throwNew(env, gJNIRefs.nullPointerException, "input is null");
        return nullptr;
    }

    jsize len = env->GetArrayLength(input);
    void* srcPtr = env->GetPrimitiveArrayCritical(input, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "GetPrimitiveArrayCritical returned null");
        return nullptr;
    }

    ZL_Report sizeReport = ZL_getDecompressedSize(srcPtr,
            static_cast<size_t>(len));
    if (ZL_isError(sizeReport)) {
        fprintf(stderr,
                "ZL_getDecompressedSize failed: error code %ld, input size %d\n",
                (long)ZL_RES_code(sizeReport),
                len);
        env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);
        return nullptr;
    }

    size_t outCap = ZL_RES_value(sizeReport);
    state->outputScratch.resize(outCap);

    ZL_Report result = ZL_DCtx_decompress(state->dctx,
            state->outputScratch.data(),
            outCap,
            srcPtr,
            static_cast<size_t>(len));

    env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);

    if (ZL_isError(result)) {
        fprintf(stderr,
                "ZL_DCtx_decompress failed: error code %ld, input size %d, output buffer size %zu\n",
                (long)ZL_RES_code(result),
                len,
                outCap);
        return nullptr;
    }

    size_t decompressedSize = ZL_RES_value(result);
    jbyteArray jresult = env->NewByteArray(static_cast<jsize>(decompressedSize));
    env->SetByteArrayRegion(jresult, 0, static_cast<jsize>(decompressedSize),
            reinterpret_cast<jbyte*>(state->outputScratch.data()));
    return jresult;
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

    if (!ensureDirect(env, src, "src")) {
        return -1;
    }
    if (!ensureDirect(env, dst, "dst")) {
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

    if (!ensureDirect(env, src, "src")) {
        return -1;
    }
    if (!ensureDirect(env, dst, "dst")) {
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
                "ZL_DCtx_decompress failed: error code %ld, input size %d, output buffer size %d\n",
                (long)ZL_RES_code(result),
                srcLen,
                dstLen);
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
        throwNew(env, gJNIRefs.nullPointerException, "input is null");
        return -1;
    }

    jsize len = env->GetArrayLength(input);
    void* ptr = env->GetPrimitiveArrayCritical(input, nullptr);
    if (ptr == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "GetPrimitiveArrayCritical returned null");
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

    if (!ensureDirect(env, src, "src")) {
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
