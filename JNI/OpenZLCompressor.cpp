#include "OpenZLCompressor.h"
#include "OpenZLNativeSupport.h"
#include "openzl/cpp/CParam.hpp"
#include "openzl/cpp/CCtx.hpp"
#include "openzl/cpp/Compressor.hpp"
#include "openzl/zl_compress.h"
#include "openzl/zl_version.h"
#include "openzl/zl_codec_output_cache.h"
#include "openzl/codecs/zl_brute_force_selector.h"
#include "cli/utils/compress_profiles.h"
#include "cli/utils/util.h"
#include "custom_parsers/sddl/sddl_profile.h"
#include "tools/sddl/compiler/Compiler.h"
#include "tools/sddl/compiler/Exception.h"
#include "tools/io/InputSetDir.h"
#include "tools/training/utils/utils.h"
#include "tools/training/train.h"
#include <cstdio>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <limits>
#include <map>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

std::string copyArrayBytes(JNIEnv* env, jbyteArray array)
{
    if (!array) throw std::invalid_argument("Byte array is required");
    std::string bytes(static_cast<size_t>(env->GetArrayLength(array)), '\0');
    if (!bytes.empty()) {
        env->GetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte*>(bytes.data()));
    }
    return bytes;
}

std::string copyJavaString(JNIEnv* env, jstring value)
{
    if (!value) throw std::invalid_argument("String is required");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

openzl::cli::ProfileArgs makeProfileArgs(
        const std::string& profile,
        const std::map<std::string, std::string>& arguments = {})
{
    openzl::arg::ArgParser parser;
    openzl::cli::ProfileArgs::addArgs(parser);

    std::vector<std::string> argvStorage = { "openzl-jni", "--profile", profile };
    auto profileArg = arguments.find("TBD");
    if (profileArg != arguments.end()) {
        argvStorage.push_back("--profile-arg");
        argvStorage.push_back(profileArg->second);
    }

    std::vector<char*> argv;
    argv.reserve(argvStorage.size());
    for (auto& arg : argvStorage) {
        argv.push_back(arg.data());
    }

    return openzl::cli::ProfileArgs(parser.parse(static_cast<int>(argv.size()), argv.data()));
}

jobjectArray trainFromDirectoryImpl(JNIEnv* env,
        const std::string& profile,
        const std::string& dir,
        jint maxTimeSecs,
        jint threads,
        jint numSamples,
        jboolean pareto,
        jint maxNumCandidates = 0,
        jint formatVersion = 0,
        bool dictionaryTraining = false,
        bool returnCandidates = false)
{
    const auto& profiles = openzl::cli::compressProfiles();
    auto it = profiles.find(profile);
    if (it == profiles.end()) {
        std::string message = "Unknown compression profile: " + profile;
        throwIllegalArgument(env, message);
        return nullptr;
    }

    try {
        if (maxTimeSecs < 0 || threads < 0 || numSamples < 0 || maxNumCandidates < 0 || formatVersion < 0) {
            throw std::invalid_argument("Training limits and formatVersion must not be negative");
        }
        openzl::tools::io::InputSetDir set(dir, false);
        std::vector<openzl::training::MultiInput> multi;
        for (const auto& inp : set) {
            openzl::training::MultiInput mi;
            mi.add(inp);
            multi.emplace_back(std::move(mi));
        }
        if (multi.empty()) {
            throw std::invalid_argument("Training directory must contain samples");
        }

        openzl::Compressor compressor;
        auto args = makeProfileArgs(profile);
        auto* profilePtr = it->second.get();
        ZL_GraphID gid = profilePtr->gen(compressor.get(), profilePtr->opaque ? profilePtr->opaque.get() : nullptr, args);
        compressor.selectStartingGraph(gid);
        compressor.setParameter(openzl::CParam::FormatVersion,
                formatVersion == 0 ? ZL_getDefaultEncodingVersion() : formatVersion);

        openzl::training::TrainParams params;
        if (threads > 0) {
            params.threads = static_cast<uint32_t>(threads);
        }
        if (numSamples > 0) {
            params.numSamples = static_cast<size_t>(numSamples);
        }
        if (maxTimeSecs > 0) {
            params.maxTimeSecs = static_cast<size_t>(maxTimeSecs);
        }
        params.paretoFrontier = pareto != JNI_FALSE;
        params.dictTraining = dictionaryTraining;
        if (maxNumCandidates > 0) {
            // Upstream crowding-distance pruning needs six points (three objectives).
            // Prune safely there, then apply the requested public result limit below.
            params.maxNumCandidates = static_cast<size_t>(maxNumCandidates < 6 ? 6 : maxNumCandidates);
        }

        params.compressorGenFunc = [profilePtr, args](
                openzl::poly::string_view serialized,
                openzl::poly::string_view dictBundle) {
            auto up = std::make_unique<openzl::Compressor>();
            try {
                ZL_GraphID gid = profilePtr->gen(up->get(), profilePtr->opaque ? profilePtr->opaque.get() : nullptr, args);
                up->selectStartingGraph(gid);
            } catch (...) {
            }
            up->deserialize(serialized, dictBundle);
            return up;
        };

        auto trained = openzl::training::train(multi, compressor, params);
        if (maxNumCandidates > 0 && trained.size() > static_cast<size_t>(maxNumCandidates)) {
            trained.resize(static_cast<size_t>(maxNumCandidates));
        }

        jclass byteArrClass = env->FindClass(returnCandidates
                ? "io/github/hybledav/TrainedCompressor" : "[B");
        if (!byteArrClass) {
            return nullptr;
        }
        jmethodID constructor = returnCandidates
                ? env->GetMethodID(byteArrClass, "<init>", "(Ljava/lang/String;[B[B)V") : nullptr;
        if (returnCandidates && !constructor) return nullptr;
        jstring profileName = returnCandidates ? env->NewStringUTF(profile.c_str()) : nullptr;
        if (returnCandidates && !profileName) return nullptr;
        jobjectArray out = env->NewObjectArray(static_cast<jsize>(trained.size()), byteArrClass, nullptr);
        if (out == nullptr) {
            return nullptr;
        }
        for (size_t i = 0; i < trained.size(); ++i) {
            const auto& serialized = trained[i].serializedCompressor;
            jbyteArray ba = env->NewByteArray(static_cast<jsize>(serialized.size()));
            if (ba == nullptr) {
                return nullptr;
            }
            if (!serialized.empty()) {
                env->SetByteArrayRegion(
                        ba,
                        0,
                        static_cast<jsize>(serialized.size()),
                        reinterpret_cast<const jbyte*>(serialized.data()));
            }
            if (env->ExceptionCheck()) return nullptr;
            if (returnCandidates) {
                const std::string bundle = trained[i].dicts.empty() ? std::string() : trained[i].packFatBundle();
                jbyteArray dict = env->NewByteArray(static_cast<jsize>(bundle.size()));
                if (!dict) return nullptr;
                if (!bundle.empty()) {
                    env->SetByteArrayRegion(dict, 0, static_cast<jsize>(bundle.size()),
                            reinterpret_cast<const jbyte*>(bundle.data()));
                    if (env->ExceptionCheck()) return nullptr;
                }
                jobject candidate = env->NewObject(byteArrClass, constructor, profileName, ba, dict);
                if (!candidate) return nullptr;
                env->SetObjectArrayElement(out, static_cast<jsize>(i), candidate);
                env->DeleteLocalRef(candidate);
                env->DeleteLocalRef(dict);
            } else {
                env->SetObjectArrayElement(out, static_cast<jsize>(i), ba);
            }
            env->DeleteLocalRef(ba);
            if (env->ExceptionCheck()) return nullptr;
        }
        return out;
    } catch (const std::invalid_argument& ex) {
        throwIllegalArgument(env, ex.what());
        return nullptr;
    } catch (const openzl::cli::InvalidArgsException& ex) {
        throwIllegalArgument(env, ex.what());
        return nullptr;
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}

} // namespace

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_setTryGraphCacheBudgetNative(
        JNIEnv* env, jobject obj, jlong bytes)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "setTryGraphCacheBudget")) return;
    if (bytes < 0 || static_cast<uint64_t>(bytes) > std::numeric_limits<size_t>::max()) {
        throwIllegalArgument(env, "Cache budget must fit a non-negative native size");
        return;
    }
    try {
        openzl::unwrap(ZL_CCtx_setTryGraphCacheBudget(state->cctx, static_cast<size_t>(bytes)));
        state->tryGraphCacheBudget = static_cast<size_t>(bytes);
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
    }
}

extern "C" JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_getTryGraphCacheBudgetNative(
        JNIEnv* env, jobject obj)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "getTryGraphCacheBudget")) return 0;
    return static_cast<jlong>(state->tryGraphCacheBudget);
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLCompressor_trainCandidatesFromDirectoryNative(
        JNIEnv* env, jclass, jstring profileName, jstring directory,
        jint maxTimeSecs, jint threads, jint numSamples, jboolean pareto,
        jint maxNumCandidates, jint formatVersion, jboolean dictionaryTraining)
{
    try {
        const auto profile = copyJavaString(env, profileName);
        if (env->ExceptionCheck()) return nullptr;
        const auto path = copyJavaString(env, directory);
        if (env->ExceptionCheck()) return nullptr;
        return trainFromDirectoryImpl(env, profile, path, maxTimeSecs, threads, numSamples, pareto,
                maxNumCandidates, formatVersion, dictionaryTraining != JNI_FALSE, true);
    } catch (const std::exception& ex) {
        throwIllegalArgument(env, ex.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_configureTrainedNative(
        JNIEnv* env, jobject obj, jstring profileName, jbyteArray serialized, jbyteArray dictionaryBundle)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "configureTrained")) return;
    try {
        const auto profile = copyJavaString(env, profileName);
        if (env->ExceptionCheck()) return;
        const auto graphBytes = copyArrayBytes(env, serialized);
        if (env->ExceptionCheck()) return;
        const auto bundle = copyArrayBytes(env, dictionaryBundle);
        if (env->ExceptionCheck()) return;
        const auto& profiles = openzl::cli::compressProfiles();
        auto it = profiles.find(profile);
        if (it == profiles.end()) throw std::invalid_argument("Unknown compression profile: " + profile);
        openzl::Compressor compressor;
        compressor.setParameter(openzl::CParam::FormatVersion, ZL_getDefaultEncodingVersion());
        auto args = makeProfileArgs(profile);
        auto* definition = it->second.get();
        compressor.selectStartingGraph(definition->gen(compressor.get(),
                definition->opaque ? definition->opaque.get() : nullptr, args));
        compressor.deserialize(graphBytes, bundle);
        if (compressor.getParameter(openzl::CParam::FormatVersion) == 0) {
            compressor.setParameter(openzl::CParam::FormatVersion, ZL_getDefaultEncodingVersion());
        }
        std::unique_ptr<openzl::FatBundleDictLoader> loader;
        if (!bundle.empty()) {
            loader = std::make_unique<openzl::FatBundleDictLoader>();
            loader->loadFatBundle(bundle);
        }
        const auto graph = compressor.getStartingGraph();
        openzl::unwrap(ZL_CCtx_resetParameters(state->cctx));
        openzl::unwrap(ZL_DCtx_resetParameters(state->dctx));
        state->compressor = std::move(compressor);
        state->startingGraph = graph;
        state->dictionaryLoader = std::move(loader);
        state->reset();
    } catch (const std::exception& ex) {
        throwIllegalArgument(env, ex.what());
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_setDictionaryBundleNative(
        JNIEnv* env, jobject obj, jbyteArray dictionaryBundle)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "setDictionaryBundle")) return;
    try {
        const auto bundle = copyArrayBytes(env, dictionaryBundle);
        if (env->ExceptionCheck()) return;
        state->setDictionaryBundle(bundle);
    } catch (const std::exception& ex) {
        throwIllegalArgument(env, ex.what());
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_configureBruteForceNative(
        JNIEnv* env, jobject obj, jintArray candidates)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "configureBruteForce")) return;
    try {
        const jsize count = env->GetArrayLength(candidates);
        std::vector<jint> ids(static_cast<size_t>(count));
        env->GetIntArrayRegion(candidates, 0, count, ids.data());
        if (env->ExceptionCheck()) return;
        std::vector<ZL_GraphID> graphs;
        for (const auto id : ids) graphs.push_back(graphIdFromOrdinal(id));
        state->setGraph(openzl::unwrap(ZL_Compressor_buildBruteForceSelectorGraph(
                state->compressor.get(), graphs.data(), graphs.size())));
    } catch (const std::exception& ex) {
        throwIllegalArgument(env, ex.what());
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_serializeCompressorNative(
        JNIEnv* env, jobject obj)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "serializeCompressor")) return nullptr;
    try {
        const auto bytes = state->compressor.serialize();
        jbyteArray output = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (output) env->SetByteArrayRegion(output, 0, static_cast<jsize>(bytes.size()),
                reinterpret_cast<const jbyte*>(bytes.data()));
        return output;
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
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
    const auto parameter = static_cast<openzl::CParam>(param);
    if (parameter == openzl::CParam::FormatVersion && value == 0) {
        value = ZL_getDefaultEncodingVersion();
    }
    try {
        state->compressor.setParameter(parameter, value);
    } catch (const std::exception& ex) {
        throwIllegalArgument(env, ex.what());
    }
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

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_destroyCompressorHandleNative(JNIEnv*, jclass, jlong handle)
{
    if (handle == 0) {
        return;
    }
    auto* state = reinterpret_cast<NativeState*>(handle);
    recycleState(state);
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

    std::map<std::string, std::string> profileArgs;
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

        profileArgs.emplace(std::string(keyChars), std::string(valueChars));
        env->ReleaseStringUTFChars(keyObj, keyChars);
        env->ReleaseStringUTFChars(valueObj, valueChars);
        env->DeleteLocalRef(keyObj);
        env->DeleteLocalRef(valueObj);
    }

    try {
        auto args = makeProfileArgs(profile, profileArgs);
        const int compressionLevel = state->compressor.getParameter(openzl::CParam::CompressionLevel);
        if (compressionLevel > 0) args.setRequestedCompressionLevel(compressionLevel);
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

    return trainFromDirectoryImpl(env, profile, dir, maxTimeSecs, threads, numSamples, pareto);
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLCompressor_trainNative(JNIEnv* env,
        jclass,
        jstring profileName,
        jobjectArray inputs,
        jint maxTimeSecs,
        jint threads,
        jint numSamples,
        jboolean pareto)
{
    if (profileName == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "profileName");
        return nullptr;
    }
    if (inputs == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "inputs");
        return nullptr;
    }

    const char* profileChars = env->GetStringUTFChars(profileName, nullptr);
    if (profileChars == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to access profileName");
        return nullptr;
    }
    std::string profile(profileChars);
    env->ReleaseStringUTFChars(profileName, profileChars);

    namespace fs = std::filesystem;
    std::error_code ec;
    fs::path tmpDir = fs::temp_directory_path(ec);
    if (ec) {
        throwIllegalState(env, "Unable to resolve temporary directory");
        return nullptr;
    }
    tmpDir /= fs::path("openzl-train-native-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    if (!fs::create_directories(tmpDir, ec) || ec) {
        throwIllegalState(env, "Unable to create temporary training directory");
        return nullptr;
    }

    jobjectArray result = nullptr;
    jsize count = env->GetArrayLength(inputs);
    for (jsize i = 0; i < count; ++i) {
        auto* arr = static_cast<jbyteArray>(env->GetObjectArrayElement(inputs, i));
        if (arr == nullptr) {
            arr = env->NewByteArray(0);
        }
        if (arr == nullptr) {
            throwNew(env, JniRefs().outOfMemoryError, "Unable to allocate empty input");
            goto cleanup;
        }
        jsize len = env->GetArrayLength(arr);
        std::string content;
        content.resize(static_cast<size_t>(len));
        if (len > 0) {
            env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(content.data()));
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(arr);
                goto cleanup;
            }
        }
        env->DeleteLocalRef(arr);

        fs::path filePath = tmpDir / std::to_string(static_cast<int>(i));
        std::ofstream out(filePath, std::ios::binary);
        if (!out) {
            throwIllegalState(env, "Unable to create temporary training file");
            goto cleanup;
        }
        out.write(content.data(), static_cast<std::streamsize>(content.size()));
        if (!out.good()) {
            throwIllegalState(env, "Unable to write temporary training file");
            goto cleanup;
        }
    }

    result = trainFromDirectoryImpl(env,
            profile,
            tmpDir.string(),
            maxTimeSecs,
            threads,
            numSamples,
            pareto);

cleanup:
    fs::remove_all(tmpDir, ec);
    return result;
}

// These entry points retain the legacy byte[][] API while honoring saved parameters.
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressWithProfileNative(
        JNIEnv* env, jclass, jstring profileName, jbyteArray input)
{
    if (!profileName || !input) {
        throwNew(env, JniRefs().nullPointerException, "profileName and input are required");
        return nullptr;
    }
    try {
        const auto profile = copyJavaString(env, profileName);
        if (env->ExceptionCheck()) return nullptr;
        const auto bytes = copyArrayBytes(env, input);
        if (env->ExceptionCheck()) return nullptr;
        const auto& profiles = openzl::cli::compressProfiles();
        auto it = profiles.find(profile);
        if (it == profiles.end()) throw std::invalid_argument("Unknown compression profile: " + profile);
        openzl::Compressor compressor;
        compressor.setParameter(openzl::CParam::FormatVersion, ZL_getDefaultEncodingVersion());
        auto args = makeProfileArgs(profile);
        auto* definition = it->second.get();
        compressor.selectStartingGraph(definition->gen(compressor.get(),
                definition->opaque ? definition->opaque.get() : nullptr, args));
        openzl::CCtx context;
        context.refCompressor(compressor);
        const auto compressed = context.compressSerial(bytes);
        jbyteArray output = env->NewByteArray(static_cast<jsize>(compressed.size()));
        if (output) env->SetByteArrayRegion(output, 0, static_cast<jsize>(compressed.size()),
                reinterpret_cast<const jbyte*>(compressed.data()));
        return output;
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressWithSerializedNative(
        JNIEnv* env, jclass, jstring profileName, jbyteArray serialized, jbyteArray input)
{
    if (!profileName || !serialized || !input) {
        throwNew(env, JniRefs().nullPointerException, "profileName, serialized and input are required");
        return nullptr;
    }
    try {
        const auto profile = copyJavaString(env, profileName);
        if (env->ExceptionCheck()) return nullptr;
        const auto graphBytes = copyArrayBytes(env, serialized);
        if (env->ExceptionCheck()) return nullptr;
        const auto bytes = copyArrayBytes(env, input);
        if (env->ExceptionCheck()) return nullptr;
        const auto& profiles = openzl::cli::compressProfiles();
        auto it = profiles.find(profile);
        if (it == profiles.end()) throw std::invalid_argument("Unknown compression profile: " + profile);
        openzl::Compressor compressor;
        compressor.setParameter(openzl::CParam::FormatVersion, ZL_getDefaultEncodingVersion());
        auto args = makeProfileArgs(profile);
        auto* definition = it->second.get();
        compressor.selectStartingGraph(definition->gen(compressor.get(),
                definition->opaque ? definition->opaque.get() : nullptr, args));
        compressor.deserialize(graphBytes);
        openzl::CCtx context;
        context.refCompressor(compressor);
        const auto compressed = context.compressSerial(bytes);
        jbyteArray output = env->NewByteArray(static_cast<jsize>(compressed.size()));
        if (output) env->SetByteArrayRegion(output, 0, static_cast<jsize>(compressed.size()),
                reinterpret_cast<const jbyte*>(compressed.data()));
        return output;
    } catch (const std::exception& ex) {
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
    if (vm != nullptr && vm->GetEnv(&envVoid, JNI_VERSION_1_6) == JNI_OK) {
        JNIEnv* env = static_cast<JNIEnv*>(envVoid);
        if (!initJniRefs(env) && env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*)
{
    void* envVoid = nullptr;
    if (vm != nullptr && vm->GetEnv(&envVoid, JNI_VERSION_1_6) == JNI_OK) {
        JNIEnv* env = static_cast<JNIEnv*>(envVoid);
        clearJniRefs(env);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
}
