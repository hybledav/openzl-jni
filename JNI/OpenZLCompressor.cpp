#include "OpenZLCompressor.h"
#include "openzl/cpp/CParam.hpp"
#include "openzl/cpp/Compressor.hpp"
#include "openzl/codecs/zl_bitpack.h"
#include "openzl/codecs/zl_constant.h"
#include "openzl/codecs/zl_entropy.h"
#include "openzl/codecs/zl_generic.h"
#include "openzl/codecs/zl_store.h"
#include "openzl/codecs/zl_zstd.h"
#include "openzl/zl_compress.h"
#include "openzl/zl_compressor.h"
#include "openzl/zl_data.h"
#include "openzl/zl_decompress.h"
#include "openzl/zl_opaque_types.h"
#include <array>
#include <limits>
#include <memory>
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

static void throwNew(JNIEnv* env, jclass clazz, const char* message);

struct NativeState {
    openzl::Compressor compressor;
    ZL_CCtx* cctx = nullptr;
    ZL_DCtx* dctx = nullptr;
    ZL_GraphID startingGraph = ZL_GRAPH_ZSTD;
    struct ScratchBuffer {
        std::unique_ptr<uint8_t[]> data;
        size_t capacity = 0;
        size_t size = 0;

        uint8_t* ensure(size_t required)
        {
            if (capacity < required) {
                data.reset(required == 0 ? nullptr : new uint8_t[required]);
                capacity = required;
            }
            return data.get();
        }

        void reset()
        {
            size = 0;
        }

        void setSize(size_t newSize)
        {
            size = newSize;
        }

        uint8_t* ptr()
        {
            return data.get();
        }

        const uint8_t* ptr() const
        {
            return data.get();
        }
    };

    ScratchBuffer outputScratch;

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

    void configureGraph()
    {
        expectSuccess(
                ZL_Compressor_selectStartingGraphID(compressor.get(), startingGraph),
                "ZL_Compressor_selectStartingGraphID");
        expectSuccess(
                ZL_CCtx_refCompressor(cctx, compressor.get()),
                "ZL_CCtx_refCompressor");
        expectSuccess(
                ZL_CCtx_selectStartingGraphID(cctx, compressor.get(), startingGraph, nullptr),
                "ZL_CCtx_selectStartingGraphID");
    }

    void setGraph(ZL_GraphID graph)
    {
        startingGraph = graph;
        configureGraph();
    }

    NativeState(ZL_GraphID graph)
            : startingGraph(graph)
    {
        cctx = ZL_CCtx_create();
        dctx = ZL_DCtx_create();
        if (!cctx || !dctx) {
            throw std::bad_alloc();
        }
        applyDefaultParameters();
        configureGraph();
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
        configureGraph();
        outputScratch.reset();
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

static bool checkArrayRange(JNIEnv* env,
        jbyteArray array,
        jint offset,
        jint length,
        const char* name)
{
    if (array == nullptr) {
        throwNew(env, gJNIRefs.nullPointerException, name);
        return false;
    }
    if (offset < 0 || length < 0) {
        throwNew(env, gJNIRefs.illegalArgumentException, "offset or length is negative");
        return false;
    }
    jsize arrayLen = env->GetArrayLength(array);
    if (offset > arrayLen || length > arrayLen - offset) {
        throwNew(env, gJNIRefs.illegalArgumentException, "offset/length out of bounds");
        return false;
    }
    return true;
}

static constexpr int MAX_GLOBAL_CACHE = 8;
static NativeState* globalCache[MAX_GLOBAL_CACHE];
static int globalCacheSize = 0;

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

static ZL_GraphID graphIdFromOrdinal(jint ordinal)
{
    switch (ordinal) {
    case 1:
        return ZL_GRAPH_COMPRESS_GENERIC;
    case 2:
        return ZL_GRAPH_NUMERIC;
    case 3:
        return ZL_GRAPH_STORE;
    case 4:
        return ZL_GRAPH_BITPACK;
    case 5:
        return ZL_GRAPH_FSE;
    case 6:
        return ZL_GRAPH_HUFFMAN;
    case 7:
        return ZL_GRAPH_ENTROPY;
    case 8:
        return ZL_GRAPH_CONSTANT;
    case 0:
    default:
        return ZL_GRAPH_ZSTD;
    }
}

static bool graphEquals(ZL_GraphID lhs, ZL_GraphID rhs)
{
    return lhs.gid == rhs.gid;
}

static jint graphOrdinalFromId(ZL_GraphID graph)
{
    if (graphEquals(graph, ZL_GRAPH_ZSTD)) {
        return 0;
    }
    if (graphEquals(graph, ZL_GRAPH_COMPRESS_GENERIC)) {
        return 1;
    }
    if (graphEquals(graph, ZL_GRAPH_NUMERIC)) {
        return 2;
    }
    if (graphEquals(graph, ZL_GRAPH_STORE)) {
        return 3;
    }
    if (graphEquals(graph, ZL_GRAPH_BITPACK)) {
        return 4;
    }
    if (graphEquals(graph, ZL_GRAPH_FSE)) {
        return 5;
    }
    if (graphEquals(graph, ZL_GRAPH_HUFFMAN)) {
        return 6;
    }
    if (graphEquals(graph, ZL_GRAPH_ENTROPY)) {
        return 7;
    }
    if (graphEquals(graph, ZL_GRAPH_CONSTANT)) {
        return 8;
    }
    return -1;
}

static jbyteArray compressNumericCommon(
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
        throwNew(env, gJNIRefs.outOfMemoryError, "Failed to allocate numeric typed reference");
        return nullptr;
    }

    ZL_Report report = ZL_CCtx_compressTypedRef(state->cctx, dstPtr, bound, typedRef);
    ZL_TypedRef_free(typedRef);

    if (ZL_isError(report)) {
        fprintf(stderr,
                "ZL_CCtx_compressTypedRef failed: error code %ld\n",
                (long)ZL_RES_code(report));
        const char* context = ZL_CCtx_getErrorContextString(state->cctx, report);
        if (context != nullptr) {
            fprintf(stderr, "ZL_CCtx_compressTypedRef context: %s\n", context);
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
        throwNew(env, gJNIRefs.illegalArgumentException, "Compressed payload is empty");
        return nullptr;
    }

    ZL_FrameInfo* frameInfo = ZL_FrameInfo_create(data, length);
    if (frameInfo == nullptr) {
        throwNew(env, gJNIRefs.illegalStateException, "Failed to create frame info");
        return nullptr;
    }

    ZL_Report formatReport = ZL_FrameInfo_getFormatVersion(frameInfo);
    if (ZL_isError(formatReport)) {
        ZL_FrameInfo_free(frameInfo);
        throwNew(env, gJNIRefs.illegalStateException, "Unable to read frame format version");
        return nullptr;
    }

    ZL_Report outputsReport = ZL_FrameInfo_getNumOutputs(frameInfo);
    if (ZL_isError(outputsReport)) {
        ZL_FrameInfo_free(frameInfo);
        throwNew(env, gJNIRefs.illegalStateException, "Unable to read frame outputs");
        return nullptr;
    }

    size_t numOutputs = ZL_RES_value(outputsReport);
    if (numOutputs == 0) {
        ZL_FrameInfo_free(frameInfo);
        throwNew(env, gJNIRefs.illegalStateException, "Frame does not expose outputs");
        return nullptr;
    }

    ZL_Report decompressedReport = ZL_FrameInfo_getDecompressedSize(frameInfo, 0);
    if (ZL_isError(decompressedReport)) {
        ZL_FrameInfo_free(frameInfo);
        throwNew(env, gJNIRefs.illegalStateException, "Unable to determine decompressed size");
        return nullptr;
    }
    size_t decompressedSize = ZL_RES_value(decompressedReport);

    ZL_Report typeReport = ZL_FrameInfo_getOutputType(frameInfo, 0);
    if (ZL_isError(typeReport)) {
        ZL_FrameInfo_free(frameInfo);
        throwNew(env, gJNIRefs.illegalStateException, "Unable to determine output type");
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

extern "C" JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_nativeCreate(JNIEnv*, jobject, jint graphOrdinal)
{
    try {
        ZL_GraphID graph = graphIdFromOrdinal(graphOrdinal);
        NativeState* state = nullptr;
        if (tlsCachedState != nullptr) {
            state = tlsCachedState;
            tlsCachedState = nullptr;
            state->reset();
            state->setGraph(graph);
        } else if (globalCacheSize > 0) {
            state = globalCache[--globalCacheSize];
            state->reset();
            state->setGraph(graph);
        } else {
            state = new NativeState(graph);
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
    state->reset();
    if (tlsCachedState == nullptr) {
        tlsCachedState = state;
    } else if (globalCacheSize < MAX_GLOBAL_CACHE) {
        globalCache[globalCacheSize++] = state;
    } else {
        delete state;
    }
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
        throwNew(env, gJNIRefs.outOfMemoryError, "Failed to access source array");
        return -1;
    }
    void* dstPtr = env->GetPrimitiveArrayCritical(dst, nullptr);
    if (dstPtr == nullptr) {
        env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
        throwNew(env, gJNIRefs.outOfMemoryError, "Failed to access destination array");
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

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressIntsNative(JNIEnv* env, jobject obj, jintArray data)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "compressInts")) {
        return nullptr;
    }
    if (data == nullptr) {
        throwNew(env, gJNIRefs.nullPointerException, "data");
        return nullptr;
    }
    jsize length = env->GetArrayLength(data);
    jint* elements = env->GetIntArrayElements(data, nullptr);
    if (elements == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "Unable to access int array");
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
        throwNew(env, gJNIRefs.nullPointerException, "data");
        return nullptr;
    }
    jsize length = env->GetArrayLength(data);
    jlong* elements = env->GetLongArrayElements(data, nullptr);
    if (elements == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "Unable to access long array");
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
        throwNew(env, gJNIRefs.nullPointerException, "data");
        return nullptr;
    }
    jsize length = env->GetArrayLength(data);
    jfloat* elements = env->GetFloatArrayElements(data, nullptr);
    if (elements == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "Unable to access float array");
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
        throwNew(env, gJNIRefs.nullPointerException, "data");
        return nullptr;
    }
    jsize length = env->GetArrayLength(data);
    jdouble* elements = env->GetDoubleArrayElements(data, nullptr);
    if (elements == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "Unable to access double array");
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
        throwNew(env, gJNIRefs.nullPointerException, "compressed");
        return nullptr;
    }
    jsize len = env->GetArrayLength(src);
    void* srcPtr = env->GetPrimitiveArrayCritical(src, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "Unable to access compressed payload");
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
        throwNew(env, gJNIRefs.illegalStateException, "Compressed stream is not an int array");
        return nullptr;
    }
    size_t elementCount = info.numElts;
    if (elementCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throwNew(env, gJNIRefs.illegalStateException, "Decompressed array is too large");
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
        throwNew(env, gJNIRefs.nullPointerException, "compressed");
        return nullptr;
    }
    jsize len = env->GetArrayLength(src);
    void* srcPtr = env->GetPrimitiveArrayCritical(src, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "Unable to access compressed payload");
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
        throwNew(env, gJNIRefs.illegalStateException, "Compressed stream is not a long array");
        return nullptr;
    }
    size_t elementCount = info.numElts;
    if (elementCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throwNew(env, gJNIRefs.illegalStateException, "Decompressed array is too large");
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
        throwNew(env, gJNIRefs.nullPointerException, "compressed");
        return nullptr;
    }
    jsize len = env->GetArrayLength(src);
    void* srcPtr = env->GetPrimitiveArrayCritical(src, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "Unable to access compressed payload");
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
        throwNew(env, gJNIRefs.illegalStateException, "Compressed stream is not a float array");
        return nullptr;
    }
    size_t elementCount = info.numElts;
    if (elementCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throwNew(env, gJNIRefs.illegalStateException, "Decompressed array is too large");
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
        throwNew(env, gJNIRefs.nullPointerException, "compressed");
        return nullptr;
    }
    jsize len = env->GetArrayLength(src);
    void* srcPtr = env->GetPrimitiveArrayCritical(src, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "Unable to access compressed payload");
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
        throwNew(env, gJNIRefs.illegalStateException, "Compressed stream is not a double array");
        return nullptr;
    }
    size_t elementCount = info.numElts;
    if (elementCount > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throwNew(env, gJNIRefs.illegalStateException, "Decompressed array is too large");
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

extern "C" JNIEXPORT jlongArray JNICALL Java_io_github_hybledav_OpenZLCompressor_describeFrameNative(JNIEnv* env, jobject, jbyteArray src)
{
    if (src == nullptr) {
        throwNew(env, gJNIRefs.nullPointerException, "compressed");
        return nullptr;
    }
    jsize len = env->GetArrayLength(src);
    void* srcPtr = env->GetPrimitiveArrayCritical(src, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, gJNIRefs.outOfMemoryError, "Unable to access compressed payload");
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
    if (buffer == nullptr) {
        throwNew(env, gJNIRefs.nullPointerException, "compressed");
        return nullptr;
    }
    if (position < 0 || length < 0) {
        throwNew(env, gJNIRefs.illegalArgumentException, "Negative position or length");
        return nullptr;
    }
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) {
        throwNew(env, gJNIRefs.illegalArgumentException, "ByteBuffer must be direct");
        return nullptr;
    }
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
        throwNew(env, gJNIRefs.outOfMemoryError, "Failed to access source array");
        return -1;
    }
    void* dstPtr = env->GetPrimitiveArrayCritical(dst, nullptr);
    if (dstPtr == nullptr) {
        env->ReleasePrimitiveArrayCritical(src, srcPtr, JNI_ABORT);
        throwNew(env, gJNIRefs.outOfMemoryError, "Failed to access destination array");
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
