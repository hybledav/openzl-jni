#include "OpenZLProtobuf.h"
#include "OpenZLNativeSupport.h"

#include <cstring>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <stdexcept>
#include <unordered_map>
#include <vector>

#include "google/protobuf/descriptor.h"
#include "google/protobuf/descriptor.pb.h"
#include "google/protobuf/descriptor_database.h"
#include "google/protobuf/dynamic_message.h"
#include "google/protobuf/util/json_util.h"
#include "openzl/cpp/CParam.hpp"
#include "openzl/shared/string_view.h"
#include "tools/protobuf/ProtoDeserializer.h"
#include "tools/protobuf/ProtoGraph.h"
#include "tools/protobuf/ProtoSerializer.h"
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
    if (length == 0) {
        return data;
    }
    void* raw = env->GetPrimitiveArrayCritical(array, nullptr);
    if (raw == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Failed to access array contents");
        return {};
    }
    std::memcpy(data.data(), raw, static_cast<size_t>(length));
    env->ReleasePrimitiveArrayCritical(array, raw, JNI_ABORT);
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

std::string requireMessageType(JNIEnv* env, jstring typeName)
{
    if (typeName == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "messageType");
        return {};
    }
    const char* chars = env->GetStringUTFChars(typeName, nullptr);
    if (chars == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Failed to read messageType");
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(typeName, chars);
    if (out.empty()) {
        throwIllegalArgument(env, "messageType must not be empty");
        return {};
    }
    return out;
}

void parseIntoMessage(
        JNIEnv* env,
        Protocol protocol,
        const std::string& payload,
        google::protobuf::Message& message,
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
        const google::protobuf::Message& message,
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

void configureSerializer(openzl::protobuf::ProtoSerializer& serializer)
{
    try {
        if (auto* compressor = serializer.getCompressor()) {
            compressor->setParameter(openzl::CParam::CompressionLevel, 0);
            compressor->setParameter(openzl::CParam::MinStreamSize, 4096);
        }
    } catch (...) {
        // Silently ignore tuning failures; conversion will fall back to defaults.
    }
}

class DescriptorRegistry {
public:
    static DescriptorRegistry& instance()
    {
        static DescriptorRegistry registry;
        return registry;
    }

    void registerDescriptorSet(const google::protobuf::FileDescriptorSet& set)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        registerDescriptorSetLocked(set);
    }

    std::unique_ptr<google::protobuf::Message> newMessage(const std::string& type)
    {
        if (type.empty()) {
            throw std::runtime_error("Protobuf message type must not be empty");
        }
        std::lock_guard<std::mutex> lock(mutex_);
        return newMessageLocked(type);
    }

    bool hasType(const std::string& type)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        return findDescriptorLocked(type) != nullptr;
    }

private:
    DescriptorRegistry()
            : generated_db_(*google::protobuf::DescriptorPool::generated_pool()),
              merged_db_(&file_db_, &generated_db_),
              pool_(&merged_db_),
              factory_(&pool_)
    {
        factory_.SetDelegateToGeneratedFactory(true);
    }

    void registerDescriptorSetLocked(const google::protobuf::FileDescriptorSet& set)
    {
        bool added = false;
        for (const auto& file : set.file()) {
            if (file_db_.Add(file)) {
                added = true;
            }
        }

        if (added) {
            for (const auto& file : set.file()) {
                if (pool_.FindFileByName(file.name()) == nullptr) {
                    throw std::runtime_error("Failed to load descriptor: " + file.name());
                }
            }
        }
    }

    const google::protobuf::Descriptor* findDescriptorLocked(const std::string& type)
    {
        if (type.empty()) {
            return nullptr;
        }
        const auto* desc = pool_.FindMessageTypeByName(type);
        if (desc != nullptr) {
            return desc;
        }
        return google::protobuf::DescriptorPool::generated_pool()->FindMessageTypeByName(type);
    }

    std::unique_ptr<google::protobuf::Message> newMessageLocked(const std::string& type)
    {
        const auto* desc = findDescriptorLocked(type);
        if (desc == nullptr) {
            throw std::runtime_error("Unknown protobuf message type: " + type);
        }

        const google::protobuf::Message* prototype = nullptr;
        if (desc->file()->pool() == &pool_) {
            prototype = factory_.GetPrototype(desc);
        } else {
            prototype = google::protobuf::MessageFactory::generated_factory()->GetPrototype(desc);
        }
        if (prototype == nullptr) {
            throw std::runtime_error("Unable to create prototype for protobuf message type: " + type);
        }
        return std::unique_ptr<google::protobuf::Message>(prototype->New());
    }

    std::mutex mutex_;
    google::protobuf::SimpleDescriptorDatabase file_db_;
    google::protobuf::DescriptorPoolDatabase generated_db_;
    google::protobuf::MergedDescriptorDatabase merged_db_;
    google::protobuf::DescriptorPool pool_;
    google::protobuf::DynamicMessageFactory factory_;
};

std::unique_ptr<google::protobuf::Message> makeMessage(const std::string& type)
{
    return DescriptorRegistry::instance().newMessage(type);
}

google::protobuf::Message& reusableMessage(const std::string& type)
{
    thread_local std::unordered_map<std::string, std::unique_ptr<google::protobuf::Message>> cache;
    auto it = cache.find(type);
    if (it == cache.end()) {
        it = cache.emplace(type, makeMessage(type)).first;
    }
    google::protobuf::Message* message = it->second.get();
    message->Clear();
    return *message;
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_convertNative(
        JNIEnv* env,
        jclass,
        jbyteArray payload,
        jint inputProtocol,
        jint outputProtocol,
        jbyteArray compressorBytes,
        jstring messageType)
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

    std::string typeName = requireMessageType(env, messageType);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    try {
        thread_local openzl::protobuf::ProtoSerializer threadSerializer;
        thread_local openzl::protobuf::ProtoDeserializer threadDeserializer;

        openzl::protobuf::ProtoSerializer localSerializer;
        openzl::protobuf::ProtoSerializer* serializerPtr = &threadSerializer;
        if (compressorBytes != nullptr) {
            std::string serialized = copyArray(env, compressorBytes);
            if (env->ExceptionCheck()) {
                return nullptr;
            }
            openzl::Compressor trained;
            trained.deserialize(serialized);
            localSerializer.setCompressor(std::move(trained));
            serializerPtr = &localSerializer;
        }
        configureSerializer(*serializerPtr);

        openzl::protobuf::ProtoDeserializer& deserializer = threadDeserializer;
        google::protobuf::Message& message = reusableMessage(typeName);
        std::string input = copyArray(env, payload);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        parseIntoMessage(env, inProto, input, message, deserializer);
        if (env->ExceptionCheck()) {
            return nullptr;
        }

        std::string result = serialiseMessage(env, outProto, message, *serializerPtr);
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
        jboolean pareto,
        jstring messageType)
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

    std::string typeName = requireMessageType(env, messageType);
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

            google::protobuf::Message& message = reusableMessage(typeName);
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

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLProtobuf_registerSchemaNative(
        JNIEnv* env,
        jclass,
        jbyteArray descriptorBytes)
{
    if (descriptorBytes == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "descriptorBytes");
        return;
    }

    std::string buffer = copyArray(env, descriptorBytes);
    if (env->ExceptionCheck()) {
        return;
    }

    google::protobuf::FileDescriptorSet set;
    if (!set.ParseFromString(buffer)) {
        throwIllegalArgument(env, "Failed to parse descriptor set");
        return;
    }

    try {
        DescriptorRegistry::instance().registerDescriptorSet(set);
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
    }
}
