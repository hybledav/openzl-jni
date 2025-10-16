#include "OpenZLNativeSupport.h"

#include <array>
#include <cstdio>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <utility>

namespace {

CachedJNIRefs gJNIRefs;

constexpr int MAX_GLOBAL_CACHE = 8;
NativeState* globalCache[MAX_GLOBAL_CACHE];
int globalCacheSize = 0;
thread_local NativeState* tlsCachedState = nullptr;

jclass makeGlobalClassRef(JNIEnv* env, const char* name)
{
    jclass local = env->FindClass(name);
    if (!local) {
        return nullptr;
    }
    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return global;
}

} // namespace

CachedJNIRefs& JniRefs()
{
    return gJNIRefs;
}

uint8_t* NativeState::ScratchBuffer::ensure(size_t required)
{
    if (capacity < required) {
        data.reset(required == 0 ? nullptr : new uint8_t[required]);
        capacity = required;
    }
    return data.get();
}

void NativeState::ScratchBuffer::reset()
{
    size = 0;
}

void NativeState::ScratchBuffer::setSize(size_t newSize)
{
    size = newSize;
}

uint8_t* NativeState::ScratchBuffer::ptr()
{
    return data.get();
}

const uint8_t* NativeState::ScratchBuffer::ptr() const
{
    return data.get();
}

NativeState::NativeState(ZL_GraphID graph)
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

NativeState::~NativeState()
{
    if (cctx) {
        ZL_CCtx_free(cctx);
    }
    if (dctx) {
        ZL_DCtx_free(dctx);
    }
}

void NativeState::expectSuccess(ZL_Report report, const char* action)
{
    if (ZL_isError(report)) {
        throw std::runtime_error(std::string(action)
                + " failed: error code "
                + std::to_string(static_cast<long>(ZL_RES_code(report))));
    }
}

void NativeState::applyDefaultParameters()
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

void NativeState::configureGraph()
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

void NativeState::setGraph(ZL_GraphID graph)
{
    startingGraph = graph;
    configureGraph();
}

void NativeState::reset()
{
    expectSuccess(ZL_CCtx_resetParameters(cctx), "ZL_CCtx_resetParameters");
    expectSuccess(ZL_DCtx_resetParameters(dctx), "ZL_DCtx_resetParameters");
    applyDefaultParameters();
    configureGraph();
    outputScratch.reset();
}

bool initJniRefs(JNIEnv* env)
{
    auto& refs = JniRefs();
    refs.compressorClass = makeGlobalClassRef(env, "io/github/hybledav/OpenZLCompressor");
    if (!refs.compressorClass) {
        return false;
    }
    refs.nativeHandleField = env->GetFieldID(refs.compressorClass, "nativeHandle", "J");
    if (!refs.nativeHandleField) {
        return false;
    }
    refs.nullPointerException = makeGlobalClassRef(env, "java/lang/NullPointerException");
    refs.illegalArgumentException = makeGlobalClassRef(env, "java/lang/IllegalArgumentException");
    refs.illegalStateException = makeGlobalClassRef(env, "java/lang/IllegalStateException");
    refs.outOfMemoryError = makeGlobalClassRef(env, "java/lang/OutOfMemoryError");
    if (!refs.nullPointerException || !refs.illegalArgumentException
            || !refs.illegalStateException || !refs.outOfMemoryError) {
        return false;
    }
    return true;
}

void clearJniRefs(JNIEnv* env)
{
    auto& refs = JniRefs();
    if (refs.compressorClass) {
        env->DeleteGlobalRef(refs.compressorClass);
        refs.compressorClass = nullptr;
    }
    if (refs.nullPointerException) {
        env->DeleteGlobalRef(refs.nullPointerException);
        refs.nullPointerException = nullptr;
    }
    if (refs.illegalArgumentException) {
        env->DeleteGlobalRef(refs.illegalArgumentException);
        refs.illegalArgumentException = nullptr;
    }
    if (refs.illegalStateException) {
        env->DeleteGlobalRef(refs.illegalStateException);
        refs.illegalStateException = nullptr;
    }
    if (refs.outOfMemoryError) {
        env->DeleteGlobalRef(refs.outOfMemoryError);
        refs.outOfMemoryError = nullptr;
    }
    refs.nativeHandleField = nullptr;
}

NativeState* acquireState(ZL_GraphID graph)
{
    NativeState* state = nullptr;
    if (tlsCachedState != nullptr) {
        state = tlsCachedState;
        tlsCachedState = nullptr;
        state->reset();
        state->setGraph(graph);
        return state;
    }
    if (globalCacheSize > 0) {
        state = globalCache[--globalCacheSize];
        state->reset();
        state->setGraph(graph);
        return state;
    }
    state = new NativeState(graph);
    return state;
}

void recycleState(NativeState* state)
{
    if (state == nullptr) {
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
}

NativeState* getState(JNIEnv* env, jobject obj)
{
    auto& refs = JniRefs();
    return reinterpret_cast<NativeState*>(
            env->GetLongField(obj, refs.nativeHandleField));
}

void setNativeHandle(JNIEnv* env, jobject obj, NativeState* value)
{
    auto& refs = JniRefs();
    env->SetLongField(obj, refs.nativeHandleField, reinterpret_cast<jlong>(value));
}

void throwNew(JNIEnv* env, jclass clazz, const char* message)
{
    env->ThrowNew(clazz, message);
}

void throwIllegalState(JNIEnv* env, const std::string& message)
{
    throwNew(env, JniRefs().illegalStateException, message.c_str());
}

void throwIllegalArgument(JNIEnv* env, const std::string& message)
{
    throwNew(env, JniRefs().illegalArgumentException, message.c_str());
}

bool ensureState(NativeState* state, const char* method)
{
    if (state != nullptr) {
        return true;
    }
    std::fprintf(stderr, "OpenZLCompressor.%s called after close()\n", method);
    return false;
}

bool checkArrayRange(JNIEnv* env,
        jbyteArray array,
        jint offset,
        jint length,
        const char* name)
{
    auto& refs = JniRefs();
    if (array == nullptr) {
        throwNew(env, refs.nullPointerException, name);
        return false;
    }
    if (offset < 0 || length < 0) {
        throwNew(env, refs.illegalArgumentException, "offset or length is negative");
        return false;
    }
    jsize arrayLen = env->GetArrayLength(array);
    if (offset > arrayLen || length > arrayLen - offset) {
        throwNew(env, refs.illegalArgumentException, "offset/length out of bounds");
        return false;
    }
    return true;
}

bool ensureDirect(JNIEnv* env, jobject buffer, const char* name)
{
    auto& refs = JniRefs();
    if (buffer == nullptr) {
        throwNew(env, refs.nullPointerException, name);
        return false;
    }
    void* addr = env->GetDirectBufferAddress(buffer);
    if (addr == nullptr) {
        throwNew(env, refs.illegalArgumentException, "ByteBuffer must be direct");
        return false;
    }
    return true;
}

bool ensureDirectRange(JNIEnv* env,
        jobject buffer,
        jint position,
        jint length,
        const char* name)
{
    auto& refs = JniRefs();
    if (!ensureDirect(env, buffer, name)) {
        return false;
    }
    if (position < 0 || length < 0) {
        throwNew(env, refs.illegalArgumentException, "Negative position or length");
        return false;
    }
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (capacity < 0) {
        throwNew(env, refs.illegalStateException, "Unable to query direct buffer capacity");
        return false;
    }
    jlong end = static_cast<jlong>(position) + static_cast<jlong>(length);
    if (end > capacity) {
        throwNew(env, refs.illegalArgumentException, "position/length exceed buffer capacity");
        return false;
    }
    return true;
}

ZL_GraphID graphIdFromOrdinal(jint ordinal)
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

bool graphEquals(ZL_GraphID lhs, ZL_GraphID rhs)
{
    return lhs.gid == rhs.gid;
}

jint graphOrdinalFromId(ZL_GraphID graph)
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
