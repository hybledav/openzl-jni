#include "OpenZLCompressor.h"
#include "OpenZLNativeSupport.h"
#include "openzl/cpp/CParam.hpp"
#include "openzl/cpp/Compressor.hpp"
#include "openzl/zl_compress.h"
#include "custom_parsers/sddl/sddl_profile.h"
#include "tools/sddl/compiler/Compiler.h"
#include "tools/sddl/compiler/Exception.h"
#include <cstdio>
#include <limits>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>

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
