#include "OpenZLCompressor.h"
#include "openzl/cpp/CParam.hpp"
#include "openzl/cpp/Compressor.hpp"
#include "openzl/zl_compress.h"
#include "openzl/zl_decompress.h"
#include "openzl/zl_opaque_types.h"
#include <string>
#include <vector>

namespace {

struct NativeState {
    openzl::Compressor compressor;
    ZL_CCtx* cctx = nullptr;
    ZL_DCtx* dctx = nullptr;
    std::vector<uint8_t> inputScratch;
    std::vector<uint8_t> outputScratch;

    NativeState()
    {
        cctx = ZL_CCtx_create();
        dctx = ZL_DCtx_create();
        if (!cctx || !dctx) {
            throw std::bad_alloc();
        }
        ZL_CCtx_setParameter(cctx, ZL_CParam_stickyParameters, 1);
        ZL_CCtx_setParameter(cctx, ZL_CParam_compressionLevel, ZL_COMPRESSIONLEVEL_DEFAULT);
        ZL_CCtx_setParameter(cctx, ZL_CParam_formatVersion, ZL_getDefaultEncodingVersion());
        ZL_DCtx_setParameter(dctx, ZL_DParam_stickyParameters, 1);
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
};

static jfieldID getNativeHandleField(JNIEnv* env, jobject obj)
{
    jclass cls = env->GetObjectClass(obj);
    return env->GetFieldID(cls, "nativeHandle", "J");
}

static NativeState* getState(JNIEnv* env, jobject obj)
{
    jfieldID fid = getNativeHandleField(env, obj);
    return reinterpret_cast<NativeState*>(env->GetLongField(obj, fid));
}

static void setNativeHandle(JNIEnv* env, jobject obj, NativeState* value)
{
    jfieldID fid = getNativeHandleField(env, obj);
    env->SetLongField(obj, fid, reinterpret_cast<jlong>(value));
}

static bool ensureState(NativeState* state, const char* method)
{
    if (state != nullptr) {
        return true;
    }
    fprintf(stderr, "OpenZLCompressor.%s called after close()\n", method);
    return false;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_createCompressor(JNIEnv*, jobject)
{
    try {
        auto* state = new NativeState();
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

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_destroyCompressor(JNIEnv* env, jobject obj)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "destroy")) {
        return;
    }
    delete state;
    setNativeHandle(env, obj, nullptr);
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compress(JNIEnv* env, jobject obj, jbyteArray input)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "compress")) {
        return nullptr;
    }

    if (input == nullptr) {
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "input is null");
        return nullptr;
    }

    jsize len = env->GetArrayLength(input);
    state->inputScratch.resize(len);
    env->GetByteArrayRegion(input, 0, len,
            reinterpret_cast<jbyte*>(state->inputScratch.data()));

    size_t bound = ZL_compressBound(static_cast<size_t>(len));
    state->outputScratch.resize(bound);

    ZL_Report result = ZL_CCtx_compress(state->cctx,
            state->outputScratch.data(),
            bound,
            state->inputScratch.data(),
            static_cast<size_t>(len));

    if (ZL_isError(result)) {
        fprintf(stderr, "ZL_CCtx_compress failed: error code %ld\n",
                (long)ZL_RES_code(result));
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
        env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "input is null");
        return nullptr;
    }

    jsize len = env->GetArrayLength(input);
    state->inputScratch.resize(len);
    env->GetByteArrayRegion(input, 0, len,
            reinterpret_cast<jbyte*>(state->inputScratch.data()));

    ZL_Report sizeReport = ZL_getDecompressedSize(state->inputScratch.data(),
            static_cast<size_t>(len));
    if (ZL_isError(sizeReport)) {
        fprintf(stderr,
                "ZL_getDecompressedSize failed: error code %ld, input size %d\n",
                (long)ZL_RES_code(sizeReport),
                len);
        return nullptr;
    }

    size_t outCap = ZL_RES_value(sizeReport);
    state->outputScratch.resize(outCap);

    ZL_Report result = ZL_DCtx_decompress(state->dctx,
            state->outputScratch.data(),
            outCap,
            state->inputScratch.data(),
            static_cast<size_t>(len));

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
