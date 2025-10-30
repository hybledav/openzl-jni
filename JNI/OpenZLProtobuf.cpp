#include "OpenZLProtobuf.h"
#include "OpenZLNativeSupport.h"

#include <memory>
#include <string>
#include <vector>

#include "google/protobuf/util/json_util.h"
#include "openzl/shared/string_view.h"
#include "tools/protobuf/ProtoDeserializer.h"
#include "tools/protobuf/ProtoGraph.h"
#include "tools/protobuf/ProtoSerializer.h"
#include "schema.pb.h"
#include "tools/training/train.h"
#include "tools/training/train_params.h"
#include "tools/training/utils/utils.h"

namespace {
enum class Protocol : jint {
    Proto = 0,
    Zl    = 1,
    Json  = 2,
};

Protocol parseProtocol(JNIEnv* env, jint value)
{
    switch (static_cast<Protocol>(value)) {
    case Protocol::Proto:
    case Protocol::Zl:
    case Protocol::Json:
        return static_cast<Protocol>(value);
    default:
        throwIllegalArgument(env, "Unsupported protocol specified");
        return Protocol::Proto; // Unreachable after exception
    }
}

std::string copyArray(JNIEnv* env, jbyteArray array)
{
    jsize length = env->GetArrayLength(array);
    std::string data;
    data.resize(static_cast<size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(
                array,
                0,
                length,
                reinterpret_cast<jbyte*>(data.data()));
    }
    return data;
}

jbyteArray makeByteArray(JNIEnv* env, const std::string& data)
{
    jbyteArray out = env->NewByteArray(static_cast<jsize>(data.size()));
    if (out == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to allocate result array");
        return nullptr;
    }
    if (!data.empty()) {
        env->SetByteArrayRegion(
                out,
                0,
                static_cast<jsize>(data.size()),
                reinterpret_cast<const jbyte*>(data.data()));
    }
    return out;
}

void parseIntoMessage(
        JNIEnv* env,
        Protocol protocol,
        const std::string& payload,
        openzl::protobuf::Schema& message,
        openzl::protobuf::ProtoDeserializer& deserializer)
{
    switch (protocol) {
    case Protocol::Proto: {
        if (!message.ParseFromString(payload)) {
            throwIllegalArgument(env, "Failed to parse protobuf payload");
        }
        return;
    }
    case Protocol::Zl: {
        deserializer.deserialize(payload, message);
        return;
    }
    case Protocol::Json: {
        auto status = google::protobuf::util::JsonStringToMessage(payload, &message);
        if (!status.ok()) {
            throwIllegalArgument(env, "Failed to parse JSON payload: " + std::string(status.message()));
        }
        return;
    }
    }
}

std::string serialiseMessage(
        JNIEnv* env,
        Protocol protocol,
        const openzl::protobuf::Schema& message,
        openzl::protobuf::ProtoSerializer& serializer)
{
    switch (protocol) {
    case Protocol::Proto: {
        std::string out;
        if (!message.SerializeToString(&out)) {
            throwIllegalState(env, "Failed to serialise protobuf message");
        }
        return out;
    }
    case Protocol::Zl:
        return serializer.serialize(message);
    case Protocol::Json: {
        std::string json;
        auto status = google::protobuf::util::MessageToJsonString(message, &json);
        if (!status.ok()) {
            throwIllegalState(env, "Failed to serialise message to JSON: " + std::string(status.message()));
        }
        return json;
    }
    }
    throwIllegalState(env, "Unhandled protocol");
    return {};
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_convertNative(
        JNIEnv* env,
        jclass,
        jbyteArray payload,
        jint inputProtocol,
        jint outputProtocol,
        jbyteArray compressorBytes)
{
    if (payload == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "payload");
        return nullptr;
    }

    Protocol inProto = parseProtocol(env, inputProtocol);
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    Protocol outProto = parseProtocol(env, outputProtocol);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    try {
        openzl::protobuf::ProtoSerializer serializer;
        if (compressorBytes != nullptr) {
            std::string serialized = copyArray(env, compressorBytes);
            openzl::Compressor trained;
            trained.deserialize(serialized);
            serializer.setCompressor(std::move(trained));
        }

        openzl::protobuf::ProtoDeserializer deserializer;
        openzl::protobuf::Schema message;
        std::string input = copyArray(env, payload);
        parseIntoMessage(env, inProto, input, message, deserializer);
        if (env->ExceptionCheck()) {
            return nullptr;
        }

        std::string result = serialiseMessage(env, outProto, message, serializer);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        return makeByteArray(env, result);
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_trainNative(
        JNIEnv* env,
        jclass,
        jobjectArray samples,
        jint inputProtocol,
        jint maxTimeSecs,
        jint threads,
        jint numSamples,
        jboolean pareto)
{
    if (samples == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "samples");
        return nullptr;
    }

    jsize count = env->GetArrayLength(samples);
    if (count == 0) {
        throwIllegalArgument(env, "samples must not be empty");
        return nullptr;
    }

    Protocol inProto = parseProtocol(env, inputProtocol);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    try {
        openzl::protobuf::ProtoSerializer serializer;
        openzl::protobuf::ProtoDeserializer deserializer;
        std::vector<openzl::training::MultiInput> multiInputs;
        multiInputs.reserve(static_cast<size_t>(count));

        for (jsize idx = 0; idx < count; ++idx) {
            auto sample = static_cast<jbyteArray>(env->GetObjectArrayElement(samples, idx));
            if (sample == nullptr) {
                throwNew(env, JniRefs().nullPointerException, "samples element");
                return nullptr;
            }
            std::string payload = copyArray(env, sample);
            env->DeleteLocalRef(sample);

            openzl::protobuf::Schema message;
            parseIntoMessage(env, inProto, payload, message, deserializer);
            if (env->ExceptionCheck()) {
                return nullptr;
            }

            auto trainingInputs = serializer.getTrainingInputs(message);
            openzl::training::MultiInput multi;
            for (auto& input : trainingInputs) {
                multi.add(std::move(input));
            }
            multiInputs.emplace_back(std::move(multi));
        }

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
        params.compressorGenFunc = [](openzl::poly::string_view serialized) {
            auto up = std::make_unique<openzl::Compressor>();
            try {
                ZL_GraphID gid = openzl::protobuf::ZL_Protobuf_registerGraph(up->get());
                up->selectStartingGraph(gid);
            } catch (...) {
            }
            up->deserialize(serialized);
            return up;
        };

        auto trained = openzl::training::train(
                multiInputs, *serializer.getCompressor(), params);

        jclass byteArrClass = env->FindClass("[B");
        if (!byteArrClass) {
            return nullptr;
        }
        jobjectArray out = env->NewObjectArray(
                static_cast<jsize>(trained.size()), byteArrClass, nullptr);
        if (out == nullptr) {
            return nullptr;
        }
        for (size_t i = 0; i < trained.size(); ++i) {
            const std::string_view& sv = *trained[i];
            jbyteArray elem = env->NewByteArray(static_cast<jsize>(sv.size()));
            if (elem == nullptr) {
                return nullptr;
            }
            if (!sv.empty()) {
                env->SetByteArrayRegion(
                        elem,
                        0,
                        static_cast<jsize>(sv.size()),
                        reinterpret_cast<const jbyte*>(sv.data()));
            }
            env->SetObjectArrayElement(out, static_cast<jsize>(i), elem);
            env->DeleteLocalRef(elem);
        }
        return out;
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}
