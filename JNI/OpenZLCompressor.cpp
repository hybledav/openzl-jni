#include "OpenZLCompressor.h"
#include "openzl/cpp/CParam.hpp"
#include "openzl/cpp/Compressor.hpp"
#include "openzl/zl_compress.h"
#include "openzl/zl_decompress.h"
#include "openzl/zl_opaque_types.h"
#include <cstring>
#include <string>
#include <vector>

namespace {

static jfieldID getNativeHandleField(JNIEnv* env, jobject obj)
{
    jclass cls = env->GetObjectClass(obj);
    return env->GetFieldID(cls, "nativeHandle", "J");
}

static openzl::Compressor* getNativeCompressor(JNIEnv* env, jobject obj)
{
    jfieldID fid = getNativeHandleField(env, obj);
    return reinterpret_cast<openzl::Compressor*>(env->GetLongField(obj, fid));
}

static void setNativeHandle(JNIEnv* env, jobject obj, openzl::Compressor* value)
{
    jfieldID fid = getNativeHandleField(env, obj);
    env->SetLongField(obj, fid, reinterpret_cast<jlong>(value));
}

static bool ensureCompressor(openzl::Compressor* compressor, const char* method)
{
    if (compressor != nullptr) {
        return true;
    }
    fprintf(stderr, "OpenZLCompressor.%s called after close()\n", method);
    return false;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_createCompressor(JNIEnv*, jobject)
{
    auto* compressor = new openzl::Compressor();
    return reinterpret_cast<jlong>(compressor);
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_setParameter(JNIEnv* env, jobject obj, jint param, jint value)
{
    auto* compressor = getNativeCompressor(env, obj);
    if (!ensureCompressor(compressor, "setParameter")) {
        return;
    }
    compressor->setParameter(static_cast<openzl::CParam>(param), value);
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_getParameter(JNIEnv* env, jobject obj, jint param)
{
    auto* compressor = getNativeCompressor(env, obj);
    if (!ensureCompressor(compressor, "getParameter")) {
        return 0;
    }
    return compressor->getParameter(static_cast<openzl::CParam>(param));
}

extern "C" JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLCompressor_serialize(JNIEnv* env, jobject obj)
{
    auto* compressor = getNativeCompressor(env, obj);
    if (!ensureCompressor(compressor, "serialize")) {
        return env->NewStringUTF("");
    }
    std::string result = compressor->serialize();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLCompressor_serializeToJson(JNIEnv* env, jobject obj)
{
    auto* compressor = getNativeCompressor(env, obj);
    if (!ensureCompressor(compressor, "serializeToJson")) {
        return env->NewStringUTF("");
    }
    std::string result = compressor->serializeToJson();
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_destroyCompressor(JNIEnv* env, jobject obj)
{
    auto* compressor = getNativeCompressor(env, obj);
    if (!ensureCompressor(compressor, "destroy")) {
        return;
    }
    delete compressor;
    setNativeHandle(env, obj, nullptr);
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compress(JNIEnv* env, jobject obj, jbyteArray input)
{
    auto* compressor = getNativeCompressor(env, obj);
    if (!ensureCompressor(compressor, "compress")) {
        return nullptr;
    }

    jsize len = env->GetArrayLength(input);
    std::vector<uint8_t> in(len);
    env->GetByteArrayRegion(input, 0, len, reinterpret_cast<jbyte*>(in.data()));
    size_t bound = ZL_compressBound(len);
    std::vector<uint8_t> out(bound);

    ZL_CCtx* cctx = ZL_CCtx_create();
    ZL_CCtx_setParameter(cctx, ZL_CParam_stickyParameters, 1);
    ZL_CCtx_setParameter(cctx, ZL_CParam_compressionLevel, ZL_COMPRESSIONLEVEL_DEFAULT);
    ZL_CCtx_setParameter(cctx, ZL_CParam_formatVersion, ZL_getDefaultEncodingVersion());
    ZL_Report result = ZL_CCtx_compress(cctx, out.data(), bound, in.data(), len);
    ZL_CCtx_free(cctx);

    if (ZL_isError(result)) {
        fprintf(stderr, "ZL_CCtx_compress failed: error code %ld\n", (long)ZL_RES_code(result));
        return nullptr;
    }

    size_t compressedSize = ZL_RES_value(result);
    jbyteArray jresult = env->NewByteArray(compressedSize);
    env->SetByteArrayRegion(jresult, 0, compressedSize, reinterpret_cast<jbyte*>(out.data()));
    return jresult;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompress(JNIEnv* env, jobject obj, jbyteArray input)
{
    auto* compressor = getNativeCompressor(env, obj);
    if (!ensureCompressor(compressor, "decompress")) {
        return nullptr;
    }

    jsize len = env->GetArrayLength(input);
    std::vector<uint8_t> in(len);
    env->GetByteArrayRegion(input, 0, len, reinterpret_cast<jbyte*>(in.data()));
    ZL_Report sizeReport = ZL_getDecompressedSize(in.data(), len);
    if (ZL_isError(sizeReport)) {
        fprintf(stderr,
                "ZL_getDecompressedSize failed: error code %ld, input size %d\n",
                (long)ZL_RES_code(sizeReport),
                len);
        return nullptr;
    }

    size_t outCap = ZL_RES_value(sizeReport);
    std::vector<uint8_t> out(outCap);
    ZL_DCtx* dctx = ZL_DCtx_create();
    ZL_Report r2 = ZL_DCtx_setParameter(dctx, ZL_DParam_stickyParameters, 1);
    if (ZL_isError(r2)) {
        fprintf(stderr, "ZL_DCtx_setParameter(stickyParameters) error: code %ld\n", (long)ZL_RES_code(r2));
    }
    ZL_Report result = ZL_DCtx_decompress(dctx, out.data(), outCap, in.data(), len);
    ZL_DCtx_free(dctx);

    if (ZL_isError(result)) {
        fprintf(stderr,
                "ZL_DCtx_decompress failed: error code %ld, input size %d, output buffer size %zu\n",
                (long)ZL_RES_code(result),
                len,
                outCap);
        return nullptr;
    }

    size_t decompressedSize = ZL_RES_value(result);
    jbyteArray jresult = env->NewByteArray(decompressedSize);
    env->SetByteArrayRegion(jresult, 0, decompressedSize, reinterpret_cast<jbyte*>(out.data()));
    return jresult;
}
