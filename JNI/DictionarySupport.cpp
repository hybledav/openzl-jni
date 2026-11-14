#include "OpenZLCompressor.h"
#include "OpenZLNativeSupport.h"
#include "openzl/zl_unique_id.h"

#include <array>
#include <memory>
#include <stdexcept>

namespace {
using BundleBytes = std::array<unsigned char, sizeof(ZL_UniqueID)>;

struct BundleReference {
    BundleBytes bytes{};
    bool present = false;
};

BundleReference bundleId(const void* data, size_t size)
{
    std::unique_ptr<ZL_FrameInfo, decltype(&ZL_FrameInfo_free)> frame(
            ZL_FrameInfo_create(data, size), ZL_FrameInfo_free);
    if (!frame) throw std::invalid_argument("Invalid OpenZL frame");
    BundleReference result;
    if (const auto* id = ZL_FrameInfo_getBundleID(frame.get())) {
        ZL_UniqueID_write(result.bytes.data(), &id->id);
        result.present = true;
    }
    return result;
}

jbyteArray javaId(JNIEnv* env, const BundleReference& id)
{
    auto result = env->NewByteArray(id.present ? static_cast<jsize>(id.bytes.size()) : 0);
    if (result && id.present) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(id.bytes.size()),
                reinterpret_cast<const jbyte*>(id.bytes.data()));
    }
    return result;
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_DictionarySupport_bundleIdNative(
        JNIEnv* env, jclass, jbyteArray frame, jint offset, jint length)
{
    if (!checkArrayRange(env, frame, offset, length, "frame")) return nullptr;
    jbyte* raw = env->GetByteArrayElements(frame, nullptr);
    if (!raw) return nullptr;
    const auto release = [&](jbyte* p) { env->ReleaseByteArrayElements(frame, p, JNI_ABORT); };
    try {
        BundleReference id;
        {
            std::unique_ptr<jbyte, decltype(release)> pinned(raw, release);
            id = bundleId(raw + offset, static_cast<size_t>(length));
        }
        return javaId(env, id);
    } catch (const std::exception& ex) {
        throwIllegalArgument(env, ex.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_DictionarySupport_bundleIdDirectNative(
        JNIEnv* env, jclass, jobject frame, jint offset, jint length)
{
    if (!ensureDirectRange(env, frame, offset, length, "frame")) return nullptr;
    try {
        auto* raw = static_cast<const unsigned char*>(env->GetDirectBufferAddress(frame));
        return javaId(env, bundleId(raw + offset, static_cast<size_t>(length)));
    } catch (const std::exception& ex) {
        throwIllegalArgument(env, ex.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_setResolvedDictionaryBundleNative(
        JNIEnv* env, jobject obj, jbyteArray bundle, jbyteArray expectedId)
{
    auto* state = getState(env, obj);
    if (!ensureState(state, "setResolvedDictionaryBundle")) return;
    if (!bundle || !expectedId || env->GetArrayLength(expectedId) != sizeof(ZL_UniqueID)) {
        throwIllegalArgument(env, "A bundle and 32-byte bundle ID are required");
        return;
    }
    try {
        std::string bytes(static_cast<size_t>(env->GetArrayLength(bundle)), '\0');
        env->GetByteArrayRegion(bundle, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return;
        BundleBytes rawId{};
        env->GetByteArrayRegion(expectedId, 0, static_cast<jsize>(rawId.size()), reinterpret_cast<jbyte*>(rawId.data()));
        if (env->ExceptionCheck()) return;
        ZL_BundleID id{};
        ZL_UniqueID_read(&id.id, rawId.data());
        auto loader = std::make_unique<openzl::FatBundleDictLoader>();
        loader->loadFatBundle(bytes);
        if (!loader->fetchDictBundle(id)) throw std::invalid_argument("Bundle does not contain the requested ID");
        // Commit only after parsing, materialization and ID validation succeed.
        ZL_DCtx_refDictLoader(state->dctx, ZL_FatBundleDictLoader_getDictLoader(loader->get()));
        state->dictionaryLoader = std::move(loader);
    } catch (const std::exception& ex) {
        throwIllegalArgument(env, ex.what());
    }
}
