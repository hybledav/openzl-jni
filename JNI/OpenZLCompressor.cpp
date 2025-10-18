#include "OpenZLCompressor.h"
#include "OpenZLNativeSupport.h"
#include "openzl/cpp/CParam.hpp"
#include "openzl/cpp/Compressor.hpp"
#include "openzl/zl_compress.h"
#include "cli/utils/compress_profiles.h"
#include "cli/utils/util.h"
#include "custom_parsers/sddl/sddl_profile.h"
#include "tools/sddl/compiler/Compiler.h"
#include "tools/sddl/compiler/Exception.h"
#include "tools/io/InputSetDir.h"
#include "tools/training/utils/utils.h"
#include "tools/training/train.h"
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

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_configureProfileNative(JNIEnv* env,
        jobject obj,
        jstring profileName,
        jobjectArray argKeys,
        jobjectArray argValues)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "configureProfile")) {
        return;
    }
    if (profileName == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "profileName");
        return;
    }

    const char* profileChars = env->GetStringUTFChars(profileName, nullptr);
    if (profileChars == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access profileName");
        return;
    }

    std::string profile(profileChars);
    env->ReleaseStringUTFChars(profileName, profileChars);

    const auto& profiles = openzl::cli::compressProfiles();
    auto it = profiles.find(profile);
    if (it == profiles.end()) {
        std::string message = "Unknown compression profile: " + profile;
        throwIllegalArgument(env, message);
        return;
    }

    jsize keyCount = argKeys != nullptr ? env->GetArrayLength(argKeys) : 0;
    jsize valueCount = argValues != nullptr ? env->GetArrayLength(argValues) : 0;
    if (keyCount != valueCount) {
        throwIllegalArgument(env, "Argument keys and values must have the same length");
        return;
    }

    openzl::cli::ProfileArgs args;
    args.name = profile;
    for (jsize i = 0; i < keyCount; ++i) {
        auto keyObj = static_cast<jstring>(env->GetObjectArrayElement(argKeys, i));
        auto valueObj = static_cast<jstring>(env->GetObjectArrayElement(argValues, i));
        if (keyObj == nullptr || valueObj == nullptr) {
            if (keyObj != nullptr) {
                env->DeleteLocalRef(keyObj);
            }
            if (valueObj != nullptr) {
                env->DeleteLocalRef(valueObj);
            }
            throwNew(env, JniRefs().nullPointerException, "arguments");
            return;
        }

        const char* keyChars = env->GetStringUTFChars(keyObj, nullptr);
        const char* valueChars = env->GetStringUTFChars(valueObj, nullptr);
        if (keyChars == nullptr || valueChars == nullptr) {
            if (keyChars != nullptr) {
                env->ReleaseStringUTFChars(keyObj, keyChars);
            }
            if (valueChars != nullptr) {
                env->ReleaseStringUTFChars(valueObj, valueChars);
            }
            env->DeleteLocalRef(keyObj);
            env->DeleteLocalRef(valueObj);
            throwNew(env, JniRefs().outOfMemoryError, "Unable to access profile arguments");
            return;
        }

        args.argmap.emplace(std::string(keyChars), std::string(valueChars));
        env->ReleaseStringUTFChars(keyObj, keyChars);
        env->ReleaseStringUTFChars(valueObj, valueChars);
        env->DeleteLocalRef(keyObj);
        env->DeleteLocalRef(valueObj);
    }

    try {
        auto* profilePtr = it->second.get();
        ZL_GraphID graph = profilePtr->gen(
                state->compressor.get(),
                profilePtr->opaque ? profilePtr->opaque.get() : nullptr,
                args);
        state->setGraph(graph);
    } catch (const openzl::cli::InvalidArgsException& ex) {
        throwIllegalArgument(env, ex.what());
        return;
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return;
    }
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

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_setDataArenaNative(JNIEnv* env,
        jobject obj,
        jint arenaOrdinal)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "setDataArena")) {
        return;
    }

    if (arenaOrdinal < 0) {
        throwNew(env, JniRefs().illegalArgumentException, "arenaOrdinal");
        return;
    }

    // Map Java ordinal to ZL_DataArenaType
    ZL_DataArenaType type = (arenaOrdinal == 1) ? ZL_DataArenaType_stack : ZL_DataArenaType_heap;

    ZL_Report r = ZL_CCtx_setDataArena(state->cctx, type);
    if (ZL_isError(r)) {
        const char* ctx = ZL_CCtx_getErrorContextString(state->cctx, r);
        std::string message = "Failed to set data arena";
        if (ctx && ctx[0] != '\0') {
            message.append(": ").append(ctx);
        }
        throwIllegalState(env, message);
        return;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLCompressor_listProfilesNative(JNIEnv* env,
        jclass)
{
    const auto& profiles = openzl::cli::compressProfiles();
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) {
        return nullptr;
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(profiles.size()), stringClass, nullptr);
    if (arr == nullptr) {
        return nullptr;
    }
    jsize idx = 0;
    for (const auto& kv : profiles) {
        const std::string& name = kv.first;
        const std::string& desc = kv.second->description;
        std::string combined = name + ":" + desc;
        jstring jstr = env->NewStringUTF(combined.c_str());
        if (jstr == nullptr) {
            return nullptr;
        }
        env->SetObjectArrayElement(arr, idx++, jstr);
        env->DeleteLocalRef(jstr);
    }
    return arr;
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLCompressor_trainFromDirectoryNative(JNIEnv* env,
        jclass,
        jstring profileName,
        jstring dirPath,
        jint maxTimeSecs,
        jint threads,
        jint numSamples,
        jboolean pareto)
{
    if (profileName == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "profileName");
        return nullptr;
    }
    if (dirPath == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "dirPath");
        return nullptr;
    }
    const char* profileChars = env->GetStringUTFChars(profileName, nullptr);
    if (profileChars == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access profileName");
        return nullptr;
    }
    std::string profile(profileChars);
    env->ReleaseStringUTFChars(profileName, profileChars);

    const char* dirChars = env->GetStringUTFChars(dirPath, nullptr);
    if (dirChars == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access dirPath");
        return nullptr;
    }
    std::string dir(dirChars);
    env->ReleaseStringUTFChars(dirPath, dirChars);

    const auto& profiles = openzl::cli::compressProfiles();
    auto it = profiles.find(profile);
    if (it == profiles.end()) {
        std::string message = "Unknown compression profile: " + profile;
        throwIllegalArgument(env, message);
        return nullptr;
    }

    try {
        // Build InputSetDir and convert to MultiInput vector by iterating
        openzl::tools::io::InputSetDir set(dir, false);
        std::vector<openzl::training::MultiInput> multi;
        for (const auto& inp : set) {
            openzl::training::MultiInput mi;
            mi.add(inp);
            multi.emplace_back(std::move(mi));
        }

        // Create a compressor using profile generator
        openzl::Compressor compressor;
        openzl::cli::ProfileArgs args;
        args.name = profile;
        auto* profilePtr = it->second.get();
        ZL_GraphID gid = profilePtr->gen(compressor.get(), profilePtr->opaque ? profilePtr->opaque.get() : nullptr, args);
        compressor.selectStartingGraph(gid);

        openzl::training::TrainParams params;
        if (threads > 0) params.threads = static_cast<uint32_t>(threads);
        if (numSamples > 0) params.numSamples = static_cast<size_t>(numSamples);
        if (maxTimeSecs > 0) params.maxTimeSecs = static_cast<size_t>(maxTimeSecs);
        params.paretoFrontier = pareto != JNI_FALSE;

        // compressorGenFunc: create a new Compressor and initialize it with the
        // same profile so any prerequisite components are registered before
        // attempting to deserialize the serialized compressor. This prevents
        // deserialization errors where a serialized compressor depends on
        // components that are not pre-registered.
        params.compressorGenFunc = [profilePtr, args] (openzl::poly::string_view serialized) -> std::unique_ptr<openzl::Compressor> {
            auto up = std::make_unique<openzl::Compressor>();
            // Apply profile generator to register components/graphs expected by
            // deserialized compressors.
            try {
                ZL_GraphID gid = profilePtr->gen(up->get(), profilePtr->opaque ? profilePtr->opaque.get() : nullptr, args);
                up->selectStartingGraph(gid);
            } catch (...) {
                // If profile initialization fails, still attempt deserialize so
                // the caller gets the original error context.
            }
            up->deserialize(serialized);
            return up;
        };

        auto trained = openzl::training::train(multi, compressor, params);

        jclass byteArrClass = env->FindClass("[B");
        if (!byteArrClass) return nullptr;
        jobjectArray out = env->NewObjectArray(static_cast<jsize>(trained.size()), byteArrClass, nullptr);
        if (out == nullptr) return nullptr;
        for (size_t i = 0; i < trained.size(); ++i) {
            const std::string_view& sv = *trained[i];
            jbyteArray ba = env->NewByteArray(static_cast<jsize>(sv.size()));
            if (ba == nullptr) return nullptr;
            if (sv.size() > 0) {
                env->SetByteArrayRegion(ba, 0, static_cast<jsize>(sv.size()), reinterpret_cast<const jbyte*>(sv.data()));
            }
            env->SetObjectArrayElement(out, static_cast<jsize>(i), ba);
            env->DeleteLocalRef(ba);
        }
        return out;
    } catch (const openzl::cli::InvalidArgsException& ex) {
        throwIllegalArgument(env, ex.what());
        return nullptr;
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}

// Compress a single input using the given profile (untrained/default compressor)
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressWithProfileNative(JNIEnv* env,
        jclass,
        jstring profileName,
        jbyteArray input)
{
    if (profileName == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "profileName");
        return nullptr;
    }
    if (input == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "input");
        return nullptr;
    }

    const char* profileChars = env->GetStringUTFChars(profileName, nullptr);
    if (!profileChars) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access profileName");
        return nullptr;
    }
    std::string profile(profileChars);
    env->ReleaseStringUTFChars(profileName, profileChars);

    const auto& profiles = openzl::cli::compressProfiles();
    auto it = profiles.find(profile);
    if (it == profiles.end()) {
        throwIllegalArgument(env, std::string("Unknown compression profile: ") + profile);
        return nullptr;
    }

    jsize len = env->GetArrayLength(input);
    void* srcPtr = env->GetPrimitiveArrayCritical(input, nullptr);
    if (srcPtr == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "GetPrimitiveArrayCritical returned null");
        return nullptr;
    }

    try {
        openzl::Compressor compressor;
        openzl::cli::ProfileArgs args;
        args.name = profile;
        auto* profilePtr = it->second.get();
        ZL_GraphID gid = profilePtr->gen(compressor.get(), profilePtr->opaque ? profilePtr->opaque.get() : nullptr, args);
        compressor.selectStartingGraph(gid);

        // Create a temporary C context and compress
        ZL_CCtx* cctx = ZL_CCtx_create();
        if (!cctx) {
            env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);
            throwNew(env, JniRefs().outOfMemoryError, "Failed to create C context");
            return nullptr;
        }
        // Apply default C context parameters similar to NativeState::applyDefaultParameters
        ZL_Report rp;
        rp = ZL_CCtx_setParameter(cctx, ZL_CParam_stickyParameters, 1);
        if (ZL_isError(rp)) {
            ZL_CCtx_free(cctx);
            env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);
            throwIllegalState(env, "Failed to set cctx parameter stickyParameters");
            return nullptr;
        }
        rp = ZL_CCtx_setParameter(cctx, ZL_CParam_compressionLevel, ZL_COMPRESSIONLEVEL_DEFAULT);
        if (ZL_isError(rp)) {
            ZL_CCtx_free(cctx);
            env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);
            throwIllegalState(env, "Failed to set cctx parameter compressionLevel");
            return nullptr;
        }
        rp = ZL_CCtx_setParameter(cctx, ZL_CParam_formatVersion, ZL_getDefaultEncodingVersion());
        if (ZL_isError(rp)) {
            ZL_CCtx_free(cctx);
            env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);
            throwIllegalState(env, "Failed to set cctx parameter formatVersion");
            return nullptr;
        }

        ZL_Report r1 = ZL_CCtx_refCompressor(cctx, compressor.get());
        if (ZL_isError(r1)) {
            ZL_CCtx_free(cctx);
            env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);
            throwIllegalState(env, "Failed to bind compressor to C context");
            return nullptr;
        }
        ZL_Report r2 = ZL_CCtx_selectStartingGraphID(cctx, compressor.get(), gid, nullptr);
        if (ZL_isError(r2)) {
            ZL_CCtx_free(cctx);
            env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);
            throwIllegalState(env, "Failed to select starting graph");
            return nullptr;
        }

        size_t bound = ZL_compressBound(static_cast<size_t>(len));
        std::unique_ptr<uint8_t[]> dst(new uint8_t[bound]);
        ZL_Report result = ZL_CCtx_compress(cctx, dst.get(), bound, srcPtr, static_cast<size_t>(len));
        env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);
        ZL_CCtx_free(cctx);
        if (ZL_isError(result)) {
            throwIllegalState(env, "Compression failed for profile compressor");
            return nullptr;
        }
        size_t compressedSize = ZL_RES_value(result);
        jbyteArray out = env->NewByteArray(static_cast<jsize>(compressedSize));
        if (compressedSize > 0) {
            env->SetByteArrayRegion(out, 0, static_cast<jsize>(compressedSize), reinterpret_cast<const jbyte*>(dst.get()));
        }
        return out;
    } catch (const std::exception& ex) {
        env->ReleasePrimitiveArrayCritical(input, srcPtr, JNI_ABORT);
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}

// Compress a single input using a serialized compressor blob. The first jstring is the profile name
// (used as a hint to initialize necessary components before deserializing), second arg is serialized bytes,
// third is the input.
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressWithSerializedNative(JNIEnv* env,
        jclass,
        jstring profileName,
        jbyteArray serialized,
        jbyteArray input)
{
    if (profileName == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "profileName");
        return nullptr;
    }
    if (serialized == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "serialized");
        return nullptr;
    }
    if (input == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "input");
        return nullptr;
    }

    const char* profileChars = env->GetStringUTFChars(profileName, nullptr);
    if (!profileChars) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access profileName");
        return nullptr;
    }
    std::string profile(profileChars);
    env->ReleaseStringUTFChars(profileName, profileChars);

    jsize serLen = env->GetArrayLength(serialized);
    jbyte* serPtr = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(serialized, nullptr));
    if (serPtr == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Failed to access serialized array");
        return nullptr;
    }

    jsize inLen = env->GetArrayLength(input);
    void* inPtr = env->GetPrimitiveArrayCritical(input, nullptr);
    if (inPtr == nullptr) {
        env->ReleasePrimitiveArrayCritical(serialized, serPtr, JNI_ABORT);
        throwNew(env, JniRefs().outOfMemoryError, "Failed to access input array");
        return nullptr;
    }

    try {
        // Prepare compressor by initializing profile components
        openzl::Compressor compressor;
        const auto& profiles = openzl::cli::compressProfiles();
        auto it = profiles.find(profile);
        if (it != profiles.end()) {
            openzl::cli::ProfileArgs args;
            args.name = profile;
            auto* profilePtr = it->second.get();
            try {
                ZL_GraphID gid = profilePtr->gen(compressor.get(), profilePtr->opaque ? profilePtr->opaque.get() : nullptr, args);
                compressor.selectStartingGraph(gid);
            } catch (...) {
                // ignore: profile init best-effort
            }
        }

        // Attempt deserialize
        std::string_view sv(reinterpret_cast<const char*>(serPtr), static_cast<size_t>(serLen));
        compressor.deserialize(openzl::poly::string_view(sv.data(), sv.size()));

        // Use compressor via a temporary C ctx
        ZL_CCtx* cctx = ZL_CCtx_create();
        if (!cctx) {
            env->ReleasePrimitiveArrayCritical(serialized, serPtr, JNI_ABORT);
            env->ReleasePrimitiveArrayCritical(input, inPtr, JNI_ABORT);
            throwNew(env, JniRefs().outOfMemoryError, "Failed to create C context");
            return nullptr;
        }
        // Ensure C context has sensible defaults
        ZL_Report rp2;
        rp2 = ZL_CCtx_setParameter(cctx, ZL_CParam_stickyParameters, 1);
        if (ZL_isError(rp2)) {
            ZL_CCtx_free(cctx);
            env->ReleasePrimitiveArrayCritical(serialized, serPtr, JNI_ABORT);
            env->ReleasePrimitiveArrayCritical(input, inPtr, JNI_ABORT);
            throwIllegalState(env, "Failed to set cctx parameter stickyParameters");
            return nullptr;
        }
        rp2 = ZL_CCtx_setParameter(cctx, ZL_CParam_compressionLevel, ZL_COMPRESSIONLEVEL_DEFAULT);
        if (ZL_isError(rp2)) {
            ZL_CCtx_free(cctx);
            env->ReleasePrimitiveArrayCritical(serialized, serPtr, JNI_ABORT);
            env->ReleasePrimitiveArrayCritical(input, inPtr, JNI_ABORT);
            throwIllegalState(env, "Failed to set cctx parameter compressionLevel");
            return nullptr;
        }
        rp2 = ZL_CCtx_setParameter(cctx, ZL_CParam_formatVersion, ZL_getDefaultEncodingVersion());
        if (ZL_isError(rp2)) {
            ZL_CCtx_free(cctx);
            env->ReleasePrimitiveArrayCritical(serialized, serPtr, JNI_ABORT);
            env->ReleasePrimitiveArrayCritical(input, inPtr, JNI_ABORT);
            throwIllegalState(env, "Failed to set cctx parameter formatVersion");
            return nullptr;
        }

        ZL_Report r1 = ZL_CCtx_refCompressor(cctx, compressor.get());
        if (ZL_isError(r1)) {
            ZL_CCtx_free(cctx);
            env->ReleasePrimitiveArrayCritical(serialized, serPtr, JNI_ABORT);
            env->ReleasePrimitiveArrayCritical(input, inPtr, JNI_ABORT);
            throwIllegalState(env, "Failed to bind compressor to C context");
            return nullptr;
        }

        size_t bound = ZL_compressBound(static_cast<size_t>(inLen));
        std::unique_ptr<uint8_t[]> dst(new uint8_t[bound]);
        ZL_Report result = ZL_CCtx_compress(cctx, dst.get(), bound, inPtr, static_cast<size_t>(inLen));
        env->ReleasePrimitiveArrayCritical(serialized, serPtr, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(input, inPtr, JNI_ABORT);
        ZL_CCtx_free(cctx);
        if (ZL_isError(result)) {
            throwIllegalState(env, "Compression failed for serialized compressor");
            return nullptr;
        }
        size_t compressedSize = ZL_RES_value(result);
        jbyteArray out = env->NewByteArray(static_cast<jsize>(compressedSize));
        if (compressedSize > 0) {
            env->SetByteArrayRegion(out, 0, static_cast<jsize>(compressedSize), reinterpret_cast<const jbyte*>(dst.get()));
        }
        return out;
    } catch (const std::exception& ex) {
        env->ReleasePrimitiveArrayCritical(serialized, serPtr, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(input, inPtr, JNI_ABORT);
        throwIllegalState(env, ex.what());
        return nullptr;
    }
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
