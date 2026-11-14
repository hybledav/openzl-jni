#include "OpenZLCompressor.h"
#include "OpenZLNativeSupport.h"
#include "openzl/cpp/CCtx.hpp"
#include "openzl/cpp/DCtx.hpp"
#include "openzl/cpp/Input.hpp"
#include "openzl/cpp/FrameInfo.hpp"
#include "openzl/cpp/Output.hpp"
#include "openzl/codecs/zl_lz.h"
#include <cstring>
#include <limits>
#include <vector>

namespace {
// Every Java array is copied before calling OpenZL. Native backing is aligned even for
// numeric streams created from an unaligned direct-buffer slice on the Java side.
struct PendingJavaException {};
void checkJava(JNIEnv* env) { if (env->ExceptionCheck()) throw PendingJavaException{}; }

template<class T> struct Local {
    JNIEnv* env;
    T value;
    Local(JNIEnv* e, T v) : env(e), value(v) { checkJava(e); }
    ~Local() { if (value) env->DeleteLocalRef(value); }
    Local(const Local&) = delete;
};
void nativeFailure(JNIEnv* env, const char* operation, int code,
                   const char* message, const char* context)
{
    if (env->ExceptionCheck()) return;
    Local<jclass> cls(env, env->FindClass("io/github/hybledav/OpenZLException"));
    jmethodID ctor = env->GetMethodID(cls.value, "<init>", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    checkJava(env);
    Local<jstring> op(env, env->NewStringUTF(operation));
    Local<jstring> msg(env, env->NewStringUTF(message));
    Local<jstring> ctx(env, env->NewStringUTF(context));
    Local<jobject> error(env, env->NewObject(cls.value, ctor, code, op.value, msg.value, ctx.value));
    env->Throw(static_cast<jthrowable>(error.value));
}
void translate(JNIEnv* env, const char* op)
{
    try { throw; }
    catch (const PendingJavaException&) {}
    catch (const std::bad_alloc&) { throwNew(env, JniRefs().outOfMemoryError, "Native allocation failed"); }
    catch (const openzl::Exception& e) {
        try {
            const std::string context(e.errorContext().data(), e.errorContext().size());
            nativeFailure(env, op, e.code() ? static_cast<int>(*e.code()) : -1, e.what(), context.c_str());
        } catch (const PendingJavaException&) {}
    }
    catch (const std::exception& e) {
        try { nativeFailure(env, op, -1, e.what(), ""); }
        catch (const PendingJavaException&) {}
    }
}
std::string bytes(JNIEnv* env, jbyteArray data)
{
    std::string result(static_cast<size_t>(env->GetArrayLength(data)), '\0');
    if (!result.empty()) env->GetByteArrayRegion(data, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte*>(result.data()));
    checkJava(env);
    return result;
}
jsize javaSize(size_t size) {
    if (size > static_cast<size_t>(std::numeric_limits<jsize>::max())) throw std::length_error("Output exceeds Java array limit");
    return static_cast<jsize>(size);
}
jbyteArray byteArray(JNIEnv* env, const void* ptr, size_t size) {
    auto result = env->NewByteArray(javaSize(size));
    checkJava(env);
    if (size) env->SetByteArrayRegion(result, 0, javaSize(size), static_cast<const jbyte*>(ptr));
    checkJava(env);
    return result;
}
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressInputsNative(
        JNIEnv* env, jobject self, jobjectArray descriptors)
{
    auto* state = getState(env, self);
    if (!state) return nullptr;
    try {
        Local<jclass> cls(env, env->FindClass("io/github/hybledav/TypedInput"));
        const auto typeField = env->GetFieldID(cls.value, "type", "I"); checkJava(env);
        const auto widthField = env->GetFieldID(cls.value, "width", "I"); checkJava(env);
        const auto dataField = env->GetFieldID(cls.value, "data", "[B"); checkJava(env);
        const auto lensField = env->GetFieldID(cls.value, "lengths", "[I"); checkJava(env);
        const jsize count = env->GetArrayLength(descriptors);
        std::vector<std::vector<uint64_t>> storage(static_cast<size_t>(count));
        std::vector<std::vector<uint32_t>> lengths(static_cast<size_t>(count));
        std::vector<openzl::Input> inputs;
        inputs.reserve(count);
        for (jsize i = 0; i < count; ++i) {
            Local<jobject> descriptor(env, env->GetObjectArrayElement(descriptors, i));
            const jint type = env->GetIntField(descriptor.value, typeField); checkJava(env);
            const jint width = env->GetIntField(descriptor.value, widthField); checkJava(env);
            Local<jbyteArray> data(env, static_cast<jbyteArray>(env->GetObjectField(descriptor.value, dataField)));
            const size_t size = static_cast<size_t>(env->GetArrayLength(data.value));
            storage[i].resize(size / sizeof(uint64_t) + 1);
            if (size) env->GetByteArrayRegion(data.value, 0, javaSize(size), reinterpret_cast<jbyte*>(storage[i].data()));
            checkJava(env);
            auto* ptr = storage[i].data();
            switch (type) {
                case 0: inputs.push_back(openzl::Input::refSerial(ptr, size)); break;
                case 1: inputs.push_back(openzl::Input::refStruct(ptr, width, size / width)); break;
                case 2: inputs.push_back(openzl::Input::refNumeric(ptr, width, size / width)); break;
                case 3: {
                    Local<jintArray> lens(env, static_cast<jintArray>(env->GetObjectField(descriptor.value, lensField)));
                    const jsize n = env->GetArrayLength(lens.value);
                    lengths[i].resize(n);
                    if (n) env->GetIntArrayRegion(lens.value, 0, n, reinterpret_cast<jint*>(lengths[i].data()));
                    checkJava(env);
                    inputs.push_back(openzl::Input::refString(ptr, size, lengths[i].data(), n));
                    break;
                }
                default: throw std::invalid_argument("Unknown input type");
            }
        }
        state->compressionWarningsVisible = true;
        const auto output = openzl::CCtxRef(state->cctx).compress(inputs);
        return byteArray(env, output.data(), output.size());
    } catch (...) { translate(env, "compressInputs"); return nullptr; }
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressOutputsNative(
        JNIEnv* env, jobject self, jbyteArray frame)
{
    auto* state = getState(env, self);
    if (!state) return nullptr;
    try {
        const auto input = bytes(env, frame);
        // Reject metadata that cannot be represented by Java arrays before
        // the upstream decoder attempts to allocate the corresponding outputs.
        const openzl::FrameInfo info(input);
        javaSize(info.numOutputs());
        for (size_t i = 0; i < info.numOutputs(); ++i) {
            javaSize(info.outputContentSize(i));
            if (info.outputType(i) == openzl::Type::String) {
                javaSize(info.unwrap(ZL_FrameInfo_getNumElts(info.get(), javaSize(i))));
            }
        }
        state->decompressionWarningsVisible = true;
        const auto outputs = openzl::DCtxRef(state->dctx).decompress(input);
        Local<jclass> cls(env, env->FindClass("io/github/hybledav/TypedOutput"));
        const auto ctor = env->GetMethodID(cls.value, "<init>", "(II[B[I)V"); checkJava(env);
        auto result = env->NewObjectArray(javaSize(outputs.size()), cls.value, nullptr); checkJava(env);
        for (size_t i = 0; i < outputs.size(); ++i) {
            const auto& out = outputs[i];
            int type;
            switch (out.type()) {
                case openzl::Type::Serial: type = 0; break;
                case openzl::Type::Struct: type = 1; break;
                case openzl::Type::Numeric: type = 2; break;
                case openzl::Type::String: type = 3; break;
                default: throw std::runtime_error("Unknown output type");
            }
            Local<jbyteArray> data(env, byteArray(env, out.ptr(), out.contentSize()));
            const jsize n = type == 3 ? javaSize(out.numElts()) : 0;
            Local<jintArray> lens(env, env->NewIntArray(n));
            if (n) env->SetIntArrayRegion(lens.value, 0, n, reinterpret_cast<const jint*>(out.stringLens()));
            checkJava(env);
            Local<jobject> item(env, env->NewObject(cls.value, ctor, type, type == 3 ? 0 : javaSize(out.eltWidth()), data.value, lens.value));
            env->SetObjectArrayElement(result, javaSize(i), item.value); checkJava(env);
        }
        return result;
    } catch (...) { translate(env, "decompressOutputs"); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_setOptionsNative(
        JNIEnv* env, jobject self, jboolean decompression, jintArray values)
{
    auto* state = getState(env, self);
    if (!state) return;
    constexpr int cids[] = {2, 3, 4, 5, 6, 7, 11, 12};
    constexpr int dids[] = {2, 3, 4};
    const int* ids = decompression ? dids : cids;
    const int count = decompression ? 3 : 8;
    try {
        std::vector<jint> next(count), previous(count);
        env->GetIntArrayRegion(values, 0, count, next.data()); checkJava(env);
        for (int i = 0; i < count; ++i) previous[i] = decompression
                ? ZL_DCtx_getParameter(state->dctx, static_cast<ZL_DParam>(ids[i]))
                : ZL_CCtx_getParameter(state->cctx, static_cast<ZL_CParam>(ids[i]));
        for (int i = 0; i < count; ++i) {
            auto report = decompression
                    ? ZL_DCtx_setParameter(state->dctx, static_cast<ZL_DParam>(ids[i]), next[i])
                    : ZL_CCtx_setParameter(state->cctx, static_cast<ZL_CParam>(ids[i]), next[i]);
            if (ZL_isError(report)) {
                // Capture context before rollback calls can invalidate upstream error storage.
                const std::string context = decompression
                        ? ZL_DCtx_getErrorContextString(state->dctx, report)
                        : ZL_CCtx_getErrorContextString(state->cctx, report);
                const int code = static_cast<int>(ZL_RES_code(report));
                for (int k = 0; k <= i; ++k) {
                    if (decompression) (void)ZL_DCtx_setParameter(state->dctx, static_cast<ZL_DParam>(ids[k]), previous[k]);
                    else (void)ZL_CCtx_setParameter(state->cctx, static_cast<ZL_CParam>(ids[k]), previous[k]);
                }
                nativeFailure(env, "setOptions", code, ZL_ErrorCode_toString(static_cast<ZL_ErrorCode>(code)), context.c_str());
                return;
            }
        }
    } catch (...) { translate(env, "setOptions"); }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_configureLzNative(
        JNIEnv* env, jobject self, jintArray values)
{
    auto* state = getState(env, self);
    if (!state) return;
    try {
        constexpr int ids[] = {1, 100, 101, 103, 104, 105, 106, 10000, 10001, 102};
        jint settings[10];
        env->GetIntArrayRegion(values, 0, 10, settings); checkJava(env);
        std::vector<ZL_IntParam> params;
        // Entropy thresholds use -1 for unspecified; explicit zero is meaningful.
        for (int i = 0; i < 10; ++i) if ((i == 7 || i == 8) ? settings[i] >= 0 : settings[i] != 0) params.push_back({ids[i], settings[i]});
        ZL_LocalParams local{};
        local.intParams = {params.data(), params.size()};
        ZL_ParameterizedGraphDesc desc{};
        desc.graph = ZL_GRAPH_LZ;
        desc.localParams = &local;
        const auto graph = ZL_Compressor_registerParameterizedGraph(state->compressor.get(), &desc);
        if (graphEquals(graph, ZL_GRAPH_ILLEGAL)) throw std::runtime_error("Unable to register LZ parameters");
        state->setGraph(graph);
    } catch (...) { translate(env, "configureLz"); }
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLCompressor_getWarningsNative(
        JNIEnv* env, jobject self, jboolean decompression)
{
    auto* state = getState(env, self);
    if (!state) return nullptr;
    try {
        const bool visible = decompression ? state->decompressionWarningsVisible : state->compressionWarningsVisible;
        const auto warnings = !visible ? ZL_Error_Array{nullptr, 0}
                : decompression ? ZL_DCtx_getWarnings(state->dctx) : ZL_CCtx_getWarnings(state->cctx);
        Local<jclass> cls(env, env->FindClass("io/github/hybledav/OpenZLWarning"));
        const auto ctor = env->GetMethodID(cls.value, "<init>", "(ILjava/lang/String;Ljava/lang/String;)V"); checkJava(env);
        auto result = env->NewObjectArray(javaSize(warnings.size), cls.value, nullptr); checkJava(env);
        for (size_t i = 0; i < warnings.size; ++i) {
            const auto error = warnings.errors[i];
            Local<jstring> message(env, env->NewStringUTF(ZL_E_codeStr(error)));
            Local<jstring> context(env, env->NewStringUTF(decompression
                    ? ZL_DCtx_getErrorContextString_fromError(state->dctx, error)
                    : ZL_CCtx_getErrorContextString_fromError(state->cctx, error)));
            Local<jobject> item(env, env->NewObject(cls.value, ctor, static_cast<int>(ZL_E_code(error)), message.value, context.value));
            env->SetObjectArrayElement(result, javaSize(i), item.value); checkJava(env);
        }
        return result;
    } catch (...) { translate(env, "getWarnings"); return nullptr; }
}
