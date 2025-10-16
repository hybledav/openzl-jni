#pragma once

#include <jni.h>
#include <cstddef>
#include <memory>
#include <string>
#include "openzl/cpp/Compressor.hpp"
#include "openzl/zl_compress.h"
#include "openzl/zl_compressor.h"
#include "openzl/zl_decompress.h"
#include "openzl/zl_opaque_types.h"

struct CachedJNIRefs {
    jclass compressorClass = nullptr;
    jfieldID nativeHandleField = nullptr;
    jclass nullPointerException = nullptr;
    jclass illegalArgumentException = nullptr;
    jclass illegalStateException = nullptr;
    jclass outOfMemoryError = nullptr;
};

struct NativeState {
    struct ScratchBuffer {
        std::unique_ptr<uint8_t[]> data;
        size_t capacity = 0;
        size_t size = 0;

        uint8_t* ensure(size_t required);
        void reset();
        void setSize(size_t newSize);
        uint8_t* ptr();
        const uint8_t* ptr() const;
    };

    openzl::Compressor compressor;
    ZL_CCtx* cctx = nullptr;
    ZL_DCtx* dctx = nullptr;
    ZL_GraphID startingGraph{ ZL_GRAPH_ZSTD };
    ScratchBuffer outputScratch;

    explicit NativeState(ZL_GraphID graph);
    ~NativeState();

    NativeState(const NativeState&) = delete;
    NativeState& operator=(const NativeState&) = delete;

    void reset();
    void setGraph(ZL_GraphID graph);

private:
    static void expectSuccess(ZL_Report report, const char* action);
    void applyDefaultParameters();
    void configureGraph();
};

CachedJNIRefs& JniRefs();

bool initJniRefs(JNIEnv* env);
void clearJniRefs(JNIEnv* env);

NativeState* acquireState(ZL_GraphID graph);
void recycleState(NativeState* state);

NativeState* getState(JNIEnv* env, jobject obj);
void setNativeHandle(JNIEnv* env, jobject obj, NativeState* value);

bool ensureState(NativeState* state, const char* method);
bool checkArrayRange(JNIEnv* env, jbyteArray array, jint offset, jint length, const char* name);
bool ensureDirect(JNIEnv* env, jobject buffer, const char* name);
bool ensureDirectRange(JNIEnv* env,
        jobject buffer,
        jint position,
        jint length,
        const char* name);

void throwNew(JNIEnv* env, jclass clazz, const char* message);
void throwIllegalState(JNIEnv* env, const std::string& message);
void throwIllegalArgument(JNIEnv* env, const std::string& message);

ZL_GraphID graphIdFromOrdinal(jint ordinal);
jint graphOrdinalFromId(ZL_GraphID graph);
bool graphEquals(ZL_GraphID lhs, ZL_GraphID rhs);
