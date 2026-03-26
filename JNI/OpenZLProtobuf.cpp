#include "OpenZLProtobuf.h"
#include "OpenZLNativeSupport.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstring>
#include <memory>
#include <limits>
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
#include "openzl/zl_reflection.h"
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

struct DirectIntoPerfCounters {
    std::atomic<uint64_t> parseNs{0};
    std::atomic<uint64_t> serializeNs{0};
    std::atomic<uint64_t> writeNs{0};
    std::atomic<uint64_t> calls{0};
};

DirectIntoPerfCounters& directIntoPerfCounters()
{
    static DirectIntoPerfCounters counters;
    return counters;
}

bool directIntoPerfEnabled()
{
    static bool enabled = []() {
        const char* raw = std::getenv("OPENZL_JNI_DIRECT_INTO_PROFILE");
        return raw != nullptr && raw[0] != '\0' && std::strcmp(raw, "0") != 0;
    }();
    return enabled;
}

void recordDirectIntoPerf(uint64_t parseNs, uint64_t serializeNs, uint64_t writeNs)
{
    if (!directIntoPerfEnabled()) {
        return;
    }
    auto& counters = directIntoPerfCounters();
    counters.parseNs.fetch_add(parseNs, std::memory_order_relaxed);
    counters.serializeNs.fetch_add(serializeNs, std::memory_order_relaxed);
    counters.writeNs.fetch_add(writeNs, std::memory_order_relaxed);
    uint64_t calls = counters.calls.fetch_add(1, std::memory_order_relaxed) + 1;
    if (calls % 1000 != 0) {
        return;
    }
    double parseUs = static_cast<double>(counters.parseNs.load(std::memory_order_relaxed)) / calls / 1000.0;
    double serializeUs = static_cast<double>(counters.serializeNs.load(std::memory_order_relaxed)) / calls / 1000.0;
    double writeUs = static_cast<double>(counters.writeNs.load(std::memory_order_relaxed)) / calls / 1000.0;
    std::fprintf(stderr,
            "[openzl-jni] direct-into avg parse=%.2f us serialize=%.2f us write=%.2f us calls=%llu\n",
            parseUs,
            serializeUs,
            writeUs,
            static_cast<unsigned long long>(calls));
}

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


jint encodeRequiredLength(size_t required)
{
    if (required > static_cast<size_t>(std::numeric_limits<jint>::max() - 1)) {
        return std::numeric_limits<jint>::min();
    }
    return -static_cast<jint>(required) - 1;
}

jint writeDirectBuffer(JNIEnv* env,
        jobject outputBuffer,
        jint outputPosition,
        jint outputLength,
        const std::string& data)
{
    if (!ensureDirectRange(env, outputBuffer, outputPosition, outputLength, "output")) {
        return 0;
    }
    if (data.size() > static_cast<size_t>(std::numeric_limits<jint>::max())) {
        throwIllegalState(env, "Converted payload exceeds Java buffer limit");
        return 0;
    }
    if (data.size() > static_cast<size_t>(outputLength)) {
        return encodeRequiredLength(data.size());
    }
    void* outputAddress = env->GetDirectBufferAddress(outputBuffer);
    if (outputAddress == nullptr) {
        throwIllegalArgument(env, "output must be a direct ByteBuffer");
        return 0;
    }
    if (!data.empty()) {
        char* target = static_cast<char*>(outputAddress) + outputPosition;
        std::memcpy(target, data.data(), data.size());
    }
    return static_cast<jint>(data.size());
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
            compressor->setParameter(openzl::CParam::CompressionLevel, 1);
            compressor->setParameter(openzl::CParam::MinStreamSize, 0);
        }
    } catch (...) {
        // Silently ignore tuning failures; conversion will fall back to defaults.
    }
}

constexpr size_t kMinTrainingSamples          = 4;
constexpr size_t kMaxTrainingSamples          = 24;
constexpr size_t kMinTrainingSampleBytes      = 1 << 10;   // 1 KiB
constexpr size_t kTargetTrainingCorpusBytes   = 1 << 16;   // 64 KiB

struct SerializerCacheEntry {
    openzl::protobuf::ProtoSerializer serializer;
    size_t appliedGeneration = 0;

    SerializerCacheEntry()
    {
        configureSerializer(serializer);
    }
};

struct ExplicitSerializerCacheEntry {
    openzl::protobuf::ProtoSerializer serializer;

    explicit ExplicitSerializerCacheEntry(const std::string& serialized)
    {
        openzl::Compressor trained;
        trained.deserialize(serialized);
        serializer.setCompressor(std::move(trained));
        configureSerializer(serializer);
    }
};

class CompressorTrainingRegistry {
public:
    struct State {
        std::mutex mutex;
        bool trainingComplete        = false;
        bool trainingInProgress      = false;
        size_t generation            = 0;
        size_t totalBytes            = 0;
        std::vector<std::string> samples;
        std::string serialized;
        size_t minSamples             = kMinTrainingSamples;
        size_t targetBytes            = kTargetTrainingCorpusBytes;
    };

    static CompressorTrainingRegistry& instance()
    {
        static CompressorTrainingRegistry registry;
        return registry;
    }

    State& state(const std::string& type)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto& entry = states_[type];
        if (!entry) {
            entry = std::make_unique<State>();
        }
        return *entry;
    }

private:
    CompressorTrainingRegistry() = default;

    std::mutex mutex_;
    std::unordered_map<std::string, std::unique_ptr<State>> states_;
};

SerializerCacheEntry& serializerEntryForType(const std::string& type);
openzl::protobuf::ProtoSerializer* serializerForExplicitCompressor(
        JNIEnv* env,
        const std::string& type,
        jbyteArray compressorBytes);
void applyTrainedCompressor(
        const std::string& typeName,
        SerializerCacheEntry& entry);
void maybeAugmentTraining(
        const std::string& typeName,
        Protocol inputProtocol,
        const void* payloadPtr,
        size_t payloadLength,
        SerializerCacheEntry& entry);

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

std::string trainCompressorFromSamples(
        const std::string& typeName,
        const std::vector<std::string>& samples,
        size_t minSamples)
{
    if (samples.size() < minSamples) {
        return {};
    }

    openzl::protobuf::ProtoSerializer serializer;
    configureSerializer(serializer);
    std::unique_ptr<google::protobuf::Message> prototype = makeMessage(typeName);
    if (!prototype) {
        return {};
    }

    std::vector<openzl::training::MultiInput> multiInputs;
    multiInputs.reserve(samples.size());

    for (const std::string& sample : samples) {
        if (!prototype->ParseFromString(sample)) {
            continue;
        }
        auto inputs = serializer.getTrainingInputs(*prototype);
        openzl::training::MultiInput multi;
        for (auto& input : inputs) {
            multi.add(std::move(input));
        }
        multiInputs.emplace_back(std::move(multi));
    }

    if (multiInputs.empty()) {
        return {};
    }

    openzl::training::TrainParams params;
    params.numSamples      = multiInputs.size();
    params.paretoFrontier  = false;

    auto trained = openzl::training::train(
            multiInputs,
            *serializer.getCompressor(),
            params);
    if (trained.empty()) {
        return {};
    }
    const std::string_view& serialized = *trained.front();
    return std::string(serialized.data(), serialized.size());
}

SerializerCacheEntry& serializerEntryForType(const std::string& type)
{
    thread_local std::unordered_map<std::string, SerializerCacheEntry> cache;
    auto [it, inserted] = cache.try_emplace(type);
    (void)inserted;
    applyTrainedCompressor(type, it->second);
    return it->second;
}

openzl::protobuf::ProtoSerializer* serializerForExplicitCompressor(
        JNIEnv* env,
        const std::string& type,
        jbyteArray compressorBytes)
{
    if (compressorBytes == nullptr) {
        return nullptr;
    }

    std::string serialized = copyArray(env, compressorBytes);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    std::string key;
    key.reserve(type.size() + 1 + serialized.size());
    key.append(type);
    key.push_back('\0');
    key.append(serialized);

    thread_local std::unordered_map<std::string, std::unique_ptr<ExplicitSerializerCacheEntry>> cache;
    auto it = cache.find(key);
    if (it == cache.end()) {
        auto entry = std::make_unique<ExplicitSerializerCacheEntry>(serialized);
        it = cache.emplace(std::move(key), std::move(entry)).first;
    }
    return &it->second->serializer;
}

openzl::protobuf::ProtoSerializer& serializerForType(const std::string& type)
{
    return serializerEntryForType(type).serializer;
}

openzl::protobuf::ProtoDeserializer& deserializerForType(const std::string& type)
{
    thread_local std::unordered_map<std::string, std::unique_ptr<openzl::protobuf::ProtoDeserializer>> cache;
    auto it = cache.find(type);
    if (it == cache.end()) {
        it = cache.emplace(type, std::make_unique<openzl::protobuf::ProtoDeserializer>()).first;
    }
    return *it->second;
}

void applyTrainedCompressor(
        const std::string& typeName,
        SerializerCacheEntry& entry)
{
    auto& registryState = CompressorTrainingRegistry::instance().state(typeName);
    std::string serialized;
    size_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(registryState.mutex);
        if (!registryState.trainingComplete) {
            return;
        }
        if (registryState.serialized.empty()) {
            return;
        }
        if (registryState.generation <= entry.appliedGeneration) {
            return;
        }
        serialized = registryState.serialized;
        generation = registryState.generation;
    }

    try {
        openzl::Compressor trained;
        trained.deserialize(serialized);
        entry.serializer.setCompressor(std::move(trained));
        configureSerializer(entry.serializer);
        entry.appliedGeneration = generation;
    } catch (...) {
        // Ignore failures when applying trained state; we will keep the existing compressor.
    }
}

void maybeAugmentTraining(
        const std::string& typeName,
        Protocol inputProtocol,
        const void* payloadPtr,
        size_t payloadLength,
        SerializerCacheEntry& entry)
{
    if (inputProtocol != Protocol::Proto) {
        return;
    }
    if (payloadPtr == nullptr || payloadLength < kMinTrainingSampleBytes) {
        return;
    }

    auto& registryState = CompressorTrainingRegistry::instance().state(typeName);
    std::vector<std::string> samplesToTrain;
    size_t minSamplesForTraining = kMinTrainingSamples;
    bool shouldApplyExisting = false;
    {
        std::lock_guard<std::mutex> lock(registryState.mutex);
        minSamplesForTraining = registryState.minSamples;
        if (registryState.trainingComplete) {
            shouldApplyExisting =
                    !registryState.serialized.empty()
                    && registryState.generation > entry.appliedGeneration;
        } else if (!registryState.trainingInProgress) {
            if (registryState.samples.size() < kMaxTrainingSamples) {
                registryState.samples.emplace_back(
                        static_cast<const char*>(payloadPtr),
                        payloadLength);
                registryState.totalBytes += payloadLength;
            }
            bool thresholdReached = registryState.samples.size() >= registryState.minSamples
                    || registryState.totalBytes >= registryState.targetBytes;
            if (thresholdReached && !registryState.samples.empty()) {
                registryState.trainingInProgress = true;
                samplesToTrain = std::move(registryState.samples);
                registryState.samples.clear();
                registryState.totalBytes = 0;
            }
        }
    }

    if (shouldApplyExisting) {
        applyTrainedCompressor(typeName, entry);
        return;
    }

    if (samplesToTrain.empty()) {
        return;
    }

    std::string serialized;
    try {
        serialized = trainCompressorFromSamples(typeName, samplesToTrain, minSamplesForTraining);
    } catch (...) {
        serialized.clear();
    }

    bool publish = false;
    {
        std::lock_guard<std::mutex> lock(registryState.mutex);
        registryState.trainingInProgress = false;
        if (!serialized.empty()) {
            registryState.serialized       = serialized;
            registryState.trainingComplete = true;
            ++registryState.generation;
            publish = true;
        } else {
            // Training failed: keep gathering more samples.
            registryState.trainingComplete = false;
        }
    }

    if (publish) {
        applyTrainedCompressor(typeName, entry);
    }
}

jbyteArray convertPayload(JNIEnv* env,
        Protocol inProto,
        Protocol outProto,
        const void* payloadPtr,
        size_t payloadLength,
        jbyteArray compressorBytes,
        const std::string& typeName)
{
    try {
        openzl::protobuf::ProtoSerializer* serializerPtr = nullptr;
        SerializerCacheEntry* serializerEntry = nullptr;
        if (compressorBytes != nullptr) {
            serializerPtr = serializerForExplicitCompressor(env, typeName, compressorBytes);
            if (env->ExceptionCheck() || serializerPtr == nullptr) {
                return nullptr;
            }
        } else {
            serializerEntry = &serializerEntryForType(typeName);
            serializerPtr = &serializerEntry->serializer;
        }

        openzl::protobuf::ProtoDeserializer& deserializer =
                deserializerForType(typeName);
        google::protobuf::Message& message = reusableMessage(typeName);
        if (inProto == Protocol::Proto) {
            if (payloadLength > 0
                    && !message.ParseFromArray(payloadPtr, static_cast<int>(payloadLength))) {
                throwIllegalArgument(env, "Failed to parse protobuf payload");
                return nullptr;
            }
        } else {
            const char* charPtr = static_cast<const char*>(payloadPtr);
            std::string payload(charPtr, payloadLength);
            parseIntoMessage(env, inProto, payload, message, deserializer);
            if (env->ExceptionCheck()) {
                return nullptr;
            }
        }

        if (serializerEntry != nullptr) {
            maybeAugmentTraining(
                    typeName,
                    inProto,
                    payloadPtr,
                    payloadLength,
                    *serializerEntry);
            // Training may have swapped the compressor; refresh pointer for clarity.
            serializerPtr = &serializerEntry->serializer;
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
        jsize length = env->GetArrayLength(payload);
        jboolean isCopy = JNI_FALSE;
        jbyte* raw = env->GetByteArrayElements(payload, &isCopy);
        if (raw == nullptr) {
            throwNew(env, JniRefs().outOfMemoryError, "Failed to access payload contents");
            return nullptr;
        }
        jbyteArray result = convertPayload(env,
                inProto,
                outProto,
                static_cast<const void*>(raw),
                static_cast<size_t>(length),
                compressorBytes,
                typeName);
        env->ReleaseByteArrayElements(payload, raw, JNI_ABORT);
        return result;
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLProtobuf_configureTrainingNative(
        JNIEnv* env,
        jclass,
        jstring messageType,
        jint minSamples)
{
    if (minSamples <= 0) {
        return;
    }
    std::string typeName = requireMessageType(env, messageType);
    if (env->ExceptionCheck()) {
        return;
    }
    auto& state = CompressorTrainingRegistry::instance().state(typeName);
    std::lock_guard<std::mutex> lock(state.mutex);
    state.minSamples = static_cast<size_t>(minSamples);
    if (state.minSamples == 0) {
        state.minSamples = 1;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_convertDirectNative(
        JNIEnv* env,
        jclass,
        jobject payloadBuffer,
        jint length,
        jint inputProtocol,
        jint outputProtocol,
        jbyteArray compressorBytes,
        jstring messageType)
{
    if (payloadBuffer == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "payload");
        return nullptr;
    }
    if (length < 0) {
        throwIllegalArgument(env, "length must be non-negative");
        return nullptr;
    }

    void* directAddress = env->GetDirectBufferAddress(payloadBuffer);
    if (directAddress == nullptr) {
        throwIllegalArgument(env, "payload must be a direct ByteBuffer");
        return nullptr;
    }
    const char* payloadPtr = static_cast<const char*>(directAddress);

    Protocol inProto = parseProtocol(env, inputProtocol);
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    Protocol outProto = parseProtocol(env, outputProtocol);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    if (inProto != Protocol::Proto) {
        throwIllegalArgument(env, "Direct conversion currently supports PROTO input only");
        return nullptr;
    }

    std::string typeName = requireMessageType(env, messageType);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    try {
        openzl::protobuf::ProtoSerializer* serializerPtr = nullptr;
        SerializerCacheEntry* serializerEntry = nullptr;
        if (compressorBytes != nullptr) {
            serializerPtr = serializerForExplicitCompressor(env, typeName, compressorBytes);
            if (env->ExceptionCheck() || serializerPtr == nullptr) {
                return nullptr;
            }
        } else {
            serializerEntry = &serializerEntryForType(typeName);
            serializerPtr = &serializerEntry->serializer;
        }

        google::protobuf::Message& message = reusableMessage(typeName);
        message.Clear();
        if (length > 0 && !message.ParseFromArray(payloadPtr, static_cast<int>(length))) {
            throwIllegalArgument(env, "Failed to parse protobuf payload");
            return nullptr;
        }

        if (serializerEntry != nullptr) {
            maybeAugmentTraining(
                    typeName,
                    inProto,
                    payloadPtr,
                    static_cast<size_t>(length),
                    *serializerEntry);
            serializerPtr = &serializerEntry->serializer;
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

extern "C" JNIEXPORT jlongArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_directIntoProfileNative(
        JNIEnv* env,
        jclass)
{
    auto& counters = directIntoPerfCounters();
    jlong values[5] = {
            directIntoPerfEnabled() ? 1 : 0,
            static_cast<jlong>(counters.parseNs.load(std::memory_order_relaxed)),
            static_cast<jlong>(counters.serializeNs.load(std::memory_order_relaxed)),
            static_cast<jlong>(counters.writeNs.load(std::memory_order_relaxed)),
            static_cast<jlong>(counters.calls.load(std::memory_order_relaxed))};
    jlongArray out = env->NewLongArray(5);
    if (out == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to allocate direct-into profile snapshot");
        return nullptr;
    }
    env->SetLongArrayRegion(out, 0, 5, values);
    return out;
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLProtobuf_convertDirectIntoNative(
        JNIEnv* env,
        jclass,
        jobject payloadBuffer,
        jint length,
        jint inputProtocol,
        jint outputProtocol,
        jbyteArray compressorBytes,
        jstring messageType,
        jobject outputBuffer,
        jint outputPosition,
        jint outputLength)
{
    if (payloadBuffer == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "payload");
        return 0;
    }
    if (outputBuffer == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "output");
        return 0;
    }
    if (length < 0) {
        throwIllegalArgument(env, "length must be non-negative");
        return 0;
    }
    if (outputPosition < 0 || outputLength < 0) {
        throwIllegalArgument(env, "output position/length must be non-negative");
        return 0;
    }

    void* directAddress = env->GetDirectBufferAddress(payloadBuffer);
    if (directAddress == nullptr) {
        throwIllegalArgument(env, "payload must be a direct ByteBuffer");
        return 0;
    }
    const char* payloadPtr = static_cast<const char*>(directAddress);

    Protocol inProto = parseProtocol(env, inputProtocol);
    if (env->ExceptionCheck()) {
        return 0;
    }
    Protocol outProto = parseProtocol(env, outputProtocol);
    if (env->ExceptionCheck()) {
        return 0;
    }

    if (inProto != Protocol::Proto) {
        throwIllegalArgument(env, "Direct conversion currently supports PROTO input only");
        return 0;
    }

    std::string typeName = requireMessageType(env, messageType);
    if (env->ExceptionCheck()) {
        return 0;
    }

    try {
        openzl::protobuf::ProtoSerializer* serializerPtr = nullptr;
        SerializerCacheEntry* serializerEntry = nullptr;
        if (compressorBytes != nullptr) {
            serializerPtr = serializerForExplicitCompressor(env, typeName, compressorBytes);
            if (env->ExceptionCheck() || serializerPtr == nullptr) {
                return 0;
            }
        } else {
            serializerEntry = &serializerEntryForType(typeName);
            serializerPtr = &serializerEntry->serializer;
        }

        google::protobuf::Message& message = reusableMessage(typeName);
        message.Clear();
        auto parseStart = std::chrono::steady_clock::now();
        if (length > 0 && !message.ParseFromArray(payloadPtr, static_cast<int>(length))) {
            throwIllegalArgument(env, "Failed to parse protobuf payload");
            return 0;
        }
        uint64_t parseNs = static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::steady_clock::now() - parseStart)
                        .count());

        if (serializerEntry != nullptr) {
            maybeAugmentTraining(
                    typeName,
                    inProto,
                    payloadPtr,
                    static_cast<size_t>(length),
                    *serializerEntry);
            serializerPtr = &serializerEntry->serializer;
        }

        auto serializeStart = std::chrono::steady_clock::now();
        std::string result = serialiseMessage(env, outProto, message, *serializerPtr);
        if (env->ExceptionCheck()) {
            return 0;
        }
        uint64_t serializeNs = static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::steady_clock::now() - serializeStart)
                        .count());
        auto writeStart = std::chrono::steady_clock::now();
        jint written = writeDirectBuffer(env, outputBuffer, outputPosition, outputLength, result);
        if (!env->ExceptionCheck() && written > 0) {
            uint64_t writeNs = static_cast<uint64_t>(
                    std::chrono::duration_cast<std::chrono::nanoseconds>(
                            std::chrono::steady_clock::now() - writeStart)
                            .count());
            recordDirectIntoPerf(parseNs, serializeNs, writeNs);
        }
        return written;
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_convertSliceNative(
        JNIEnv* env,
        jclass,
        jbyteArray payload,
        jint offset,
        jint length,
        jint inputProtocol,
        jint outputProtocol,
        jbyteArray compressorBytes,
        jstring messageType)
{
    if (payload == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "payload");
        return nullptr;
    }
    if (offset < 0 || length < 0) {
        throwIllegalArgument(env, "offset and length must be non-negative");
        return nullptr;
    }
    jsize payloadLength = env->GetArrayLength(payload);
    if (offset > payloadLength || offset + length > payloadLength) {
        throwIllegalArgument(env, "offset/length exceed payload bounds");
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

    const jbyte* raw = static_cast<const jbyte*>(
            env->GetPrimitiveArrayCritical(payload, nullptr));
    if (raw == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Failed to access payload contents");
        return nullptr;
    }
    std::string input(reinterpret_cast<const char*>(raw + offset), static_cast<size_t>(length));
    env->ReleasePrimitiveArrayCritical(payload, const_cast<jbyte*>(raw), JNI_ABORT);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    try {
        return convertPayload(env,
                inProto,
                outProto,
                input.data(),
                input.size(),
                compressorBytes,
                typeName);
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

extern "C" JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLProtobuf_graphJsonNative(
        JNIEnv* env,
        jclass,
        jstring messageType)
{
    std::string typeName = requireMessageType(env, messageType);
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    try {
        SerializerCacheEntry& entry = serializerEntryForType(typeName);
        openzl::Compressor* compressor = entry.serializer.getCompressor();
        if (compressor == nullptr) {
            throwIllegalState(env, "No compressor available for graph export");
            return nullptr;
        }
        std::string json = compressor->serializeToJson();
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLProtobuf_graphJsonFromCompressorNative(
        JNIEnv* env,
        jclass,
        jbyteArray compressorBytes)
{
    if (compressorBytes == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "compressorBytes");
        return nullptr;
    }
    try {
        std::string serialized = copyArray(env, compressorBytes);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        openzl::Compressor trained;
        trained.deserialize(serialized);
        std::string json = trained.serializeToJson();
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}

namespace {
const char* graphTypeName(ZL_GraphType type)
{
    switch (type) {
    case ZL_GraphType_standard:
        return "standard";
    case ZL_GraphType_static:
        return "static";
    case ZL_GraphType_selector:
        return "selector";
    case ZL_GraphType_function:
        return "function";
    case ZL_GraphType_multiInput:
        return "multi_input";
    case ZL_GraphType_parameterized:
        return "parameterized";
    case ZL_GraphType_segmenter:
        return "segmenter";
    default:
        return "unknown";
    }
}

std::string graphNameOrId(const ZL_Compressor* compressor, ZL_GraphID graph)
{
    const char* name = ZL_Compressor_Graph_getName(compressor, graph);
    if (name != nullptr && name[0] != '\0') {
        return std::string(name);
    }
    return std::to_string(static_cast<uint64_t>(graph.gid));
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLProtobuf_graphDetailJsonNative(
        JNIEnv* env,
        jclass,
        jstring messageType)
{
    std::string typeName = requireMessageType(env, messageType);
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    try {
        SerializerCacheEntry& entry = serializerEntryForType(typeName);
        openzl::Compressor* compressor = entry.serializer.getCompressor();
        if (compressor == nullptr) {
            throwIllegalState(env, "No compressor available for graph detail export");
            return nullptr;
        }

        ZL_GraphID start{};
        bool hasStart = ZL_Compressor_getStartingGraphID(compressor->get(), &start);
        if (!hasStart) {
            throwIllegalState(env, "Starting graph not available");
            return nullptr;
        }

        std::unordered_set<uint64_t> visited;
        std::vector<ZL_GraphID> queue;
        queue.push_back(start);
        visited.insert(static_cast<uint64_t>(start.gid));

        std::ostringstream out;
        out << "{\n";
        out << "  \"start\": \"" << graphNameOrId(compressor->get(), start) << "\",\n";
        out << "  \"graphs\": [\n";

        bool firstGraph = true;
        for (size_t idx = 0; idx < queue.size(); ++idx) {
            ZL_GraphID gid = queue[idx];
            ZL_GraphType type = ZL_Compressor_getGraphType(compressor->get(), gid);
            ZL_GraphID base = ZL_Compressor_Graph_getBaseGraphID(compressor->get(), gid);
            ZL_GraphIDList successors = ZL_Compressor_Graph_getSuccessors(compressor->get(), gid);
            ZL_GraphIDList customGraphs = ZL_Compressor_Graph_getCustomGraphs(compressor->get(), gid);
            ZL_NodeIDList customNodes = ZL_Compressor_Graph_getCustomNodes(compressor->get(), gid);

            for (size_t s = 0; s < successors.nbGraphIDs; ++s) {
                uint64_t succId = static_cast<uint64_t>(successors.graphids[s].gid);
                if (visited.insert(succId).second) {
                    queue.push_back(successors.graphids[s]);
                }
            }
            for (size_t s = 0; s < customGraphs.nbGraphIDs; ++s) {
                uint64_t cid = static_cast<uint64_t>(customGraphs.graphids[s].gid);
                if (visited.insert(cid).second) {
                    queue.push_back(customGraphs.graphids[s]);
                }
            }

            if (!firstGraph) {
                out << ",\n";
            }
            firstGraph = false;

            out << "    {\n";
            out << "      \"id\": \"" << graphNameOrId(compressor->get(), gid) << "\",\n";
            out << "      \"type\": \"" << graphTypeName(type) << "\",\n";
            if (base.gid != 0) {
                out << "      \"base\": \"" << graphNameOrId(compressor->get(), base) << "\",\n";
            }
            out << "      \"successors\": [";
            for (size_t s = 0; s < successors.nbGraphIDs; ++s) {
                if (s > 0) {
                    out << ", ";
                }
                out << "\"" << graphNameOrId(compressor->get(), successors.graphids[s]) << "\"";
            }
            out << "],\n";

            out << "      \"customGraphs\": [";
            for (size_t s = 0; s < customGraphs.nbGraphIDs; ++s) {
                if (s > 0) {
                    out << ", ";
                }
                out << "\"" << graphNameOrId(compressor->get(), customGraphs.graphids[s]) << "\"";
            }
            out << "],\n";

            out << "      \"customNodes\": [";
            for (size_t n = 0; n < customNodes.nbNodeIDs; ++n) {
                if (n > 0) {
                    out << ", ";
                }
                const char* nodeName = ZL_Compressor_Node_getName(compressor->get(), customNodes.nodeids[n]);
                if (nodeName == nullptr) {
                    out << "\"\"";
                } else {
                    out << "\"" << nodeName << "\"";
                }
            }
            out << "]\n";
            out << "    }";
        }
        out << "\n  ]\n";
        out << "}\n";

        std::string json = out.str();
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}
