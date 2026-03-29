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
#include <deque>

#include "google/protobuf/descriptor.h"
#include "google/protobuf/descriptor.pb.h"
#include "google/protobuf/descriptor_database.h"
#include "google/protobuf/dynamic_message.h"
#include "google/protobuf/util/json_util.h"
#include "openzl/codecs/zl_clustering.h"
#include "openzl/cpp/CParam.hpp"
#include "openzl/shared/string_view.h"
#include "openzl/zl_reflection.h"
#include "tools/protobuf/ProtoDeserializer.h"
#include "tools/protobuf/ProtoGraph.h"
#include "tools/protobuf/ProtoSerializer.h"
#include "tools/protobuf/StringReader.h"
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

struct StructuredPerfCounters {
    std::atomic<uint64_t> pinNs{0};
    std::atomic<uint64_t> buildNs{0};
    std::atomic<uint64_t> compressNs{0};
    std::atomic<uint64_t> outNs{0};
    std::atomic<uint64_t> calls{0};
};

DirectIntoPerfCounters& directIntoPerfCounters()
{
    static DirectIntoPerfCounters counters;
    return counters;
}

StructuredPerfCounters& structuredPerfCounters()
{
    static StructuredPerfCounters counters;
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

bool structuredPerfEnabled()
{
    static bool enabled = []() {
        const char* raw = std::getenv("OPENZL_JNI_STRUCTURED_PROFILE");
        return raw != nullptr && raw[0] != '\0' && std::strcmp(raw, "0") != 0;
    }();
    return enabled;
}

constexpr int kStructuredFieldIdTag = -1;
constexpr int kStructuredFieldTypeTag = -2;
constexpr int kStructuredFieldLengthTag = -3;
constexpr uint32_t kStructuredControlMagic = 0x5A4C5053u;
constexpr uint32_t kStructuredControlFieldIds = 1u;
constexpr uint32_t kStructuredControlFieldTypes = 2u;
constexpr uint32_t kStructuredControlFieldLengths = 3u;
constexpr uint32_t kStructuredRootPathHash = 0x811c9dc5u;
constexpr uint32_t kStructuredFnvPrime = 0x01000193u;

uint32_t structuredFNV(uint32_t hash, uint32_t value);
uint32_t structuredExtendPathHash(uint32_t pathHash, uint32_t fieldNumber);
int structuredFieldTag(uint32_t pathHash, uint32_t fieldNumber);

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

void recordStructuredPerf(uint64_t pinNs, uint64_t buildNs, uint64_t compressNs, uint64_t outNs)
{
    if (!structuredPerfEnabled()) {
        return;
    }
    auto& counters = structuredPerfCounters();
    counters.pinNs.fetch_add(pinNs, std::memory_order_relaxed);
    counters.buildNs.fetch_add(buildNs, std::memory_order_relaxed);
    counters.compressNs.fetch_add(compressNs, std::memory_order_relaxed);
    counters.outNs.fetch_add(outNs, std::memory_order_relaxed);
    uint64_t calls = counters.calls.fetch_add(1, std::memory_order_relaxed) + 1;
    if (calls % 1000 != 0) {
        return;
    }
    double pinUs = static_cast<double>(counters.pinNs.load(std::memory_order_relaxed)) / calls / 1000.0;
    double buildUs = static_cast<double>(counters.buildNs.load(std::memory_order_relaxed)) / calls / 1000.0;
    double compressUs = static_cast<double>(counters.compressNs.load(std::memory_order_relaxed)) / calls / 1000.0;
    double outUs = static_cast<double>(counters.outNs.load(std::memory_order_relaxed)) / calls / 1000.0;
    std::fprintf(stderr,
            "[openzl-jni] structured avg pin=%.2f us build=%.2f us compress=%.2f us out=%.2f us calls=%llu\n",
            pinUs,
            buildUs,
            compressUs,
            outUs,
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

    const google::protobuf::Descriptor* descriptor(const std::string& type)
    {
        std::lock_guard<std::mutex> lock(mutex_);
        return findDescriptorLocked(type);
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

constexpr size_t kStructuredSerialCodecIdx = 0;
constexpr size_t kStructuredStructCodecIdx = 1;
constexpr size_t kStructuredNumericCodecIdx = 2;
constexpr size_t kStructuredStringCodecIdx = 3;

ZL_ClusteringConfig_TypeSuccessor makeStructuredTypeSuccessor(ZL_Type type, size_t eltWidth, size_t codecIdx)
{
    ZL_ClusteringConfig_TypeSuccessor successor{};
    successor.type = type;
    successor.eltWidth = eltWidth;
    successor.successorIdx = 0;
    successor.clusteringCodecIdx = codecIdx;
    return successor;
}

ZL_ClusteringConfig_TypeSuccessor makeStructuredFieldSuccessor(const google::protobuf::FieldDescriptor* field)
{
    switch (field->cpp_type()) {
        case google::protobuf::FieldDescriptor::CPPTYPE_INT32:
        case google::protobuf::FieldDescriptor::CPPTYPE_UINT32:
        case google::protobuf::FieldDescriptor::CPPTYPE_FLOAT:
        case google::protobuf::FieldDescriptor::CPPTYPE_ENUM:
            return makeStructuredTypeSuccessor(ZL_Type_numeric, 4, kStructuredNumericCodecIdx);
        case google::protobuf::FieldDescriptor::CPPTYPE_INT64:
        case google::protobuf::FieldDescriptor::CPPTYPE_UINT64:
        case google::protobuf::FieldDescriptor::CPPTYPE_DOUBLE:
            return makeStructuredTypeSuccessor(ZL_Type_numeric, 8, kStructuredNumericCodecIdx);
        case google::protobuf::FieldDescriptor::CPPTYPE_BOOL:
            return makeStructuredTypeSuccessor(ZL_Type_numeric, 1, kStructuredNumericCodecIdx);
        case google::protobuf::FieldDescriptor::CPPTYPE_STRING:
            return makeStructuredTypeSuccessor(ZL_Type_string, 0, kStructuredStringCodecIdx);
        case google::protobuf::FieldDescriptor::CPPTYPE_MESSAGE:
            break;
    }
    throw std::runtime_error("Unsupported structured protobuf field type for clustering");
}

void collectStructuredFieldClusters(
        const google::protobuf::Descriptor* descriptor,
        uint32_t pathHash,
        std::vector<std::pair<int, ZL_ClusteringConfig_TypeSuccessor>>& taggedClusters)
{
    for (int i = 0; i < descriptor->field_count(); ++i) {
        const auto* field = descriptor->field(i);
        if (field->cpp_type() == google::protobuf::FieldDescriptor::CPPTYPE_MESSAGE) {
            collectStructuredFieldClusters(
                    field->message_type(),
                    structuredExtendPathHash(pathHash, static_cast<uint32_t>(field->number())),
                    taggedClusters);
            continue;
        }
        taggedClusters.emplace_back(
                structuredFieldTag(pathHash, static_cast<uint32_t>(field->number())),
                makeStructuredFieldSuccessor(field));
    }
}

openzl::Compressor createStructuredCompressorForType(const std::string& typeName)
{
    const auto* descriptor = DescriptorRegistry::instance().descriptor(typeName);
    if (descriptor == nullptr) {
        throw std::runtime_error("Unknown protobuf message type: " + typeName);
    }

    openzl::Compressor compressor;
    auto ace = ZL_Compressor_buildACEGraph(compressor.get());
    const ZL_GraphID successors[1] = { ace };

    std::vector<std::pair<int, ZL_ClusteringConfig_TypeSuccessor>> taggedClusters;
    taggedClusters.reserve(static_cast<size_t>(descriptor->field_count()) + 3u);

    auto controlSuccessor = makeStructuredTypeSuccessor(ZL_Type_numeric, 4, kStructuredNumericCodecIdx);
    taggedClusters.emplace_back(kStructuredFieldIdTag, controlSuccessor);
    taggedClusters.emplace_back(kStructuredFieldTypeTag, controlSuccessor);
    taggedClusters.emplace_back(kStructuredFieldLengthTag, controlSuccessor);
    collectStructuredFieldClusters(descriptor, kStructuredRootPathHash, taggedClusters);

    std::vector<int> memberTags(taggedClusters.size());
    std::vector<ZL_ClusteringConfig_Cluster> clusters(taggedClusters.size());
    for (size_t i = 0; i < taggedClusters.size(); ++i) {
        memberTags[i] = taggedClusters[i].first;
        clusters[i].typeSuccessor = taggedClusters[i].second;
        clusters[i].memberTags = &memberTags[i];
        clusters[i].nbMemberTags = 1;
    }

    ZL_ClusteringConfig config{};
    config.clusters = clusters.data();
    config.nbClusters = clusters.size();
    config.typeDefaults = nullptr;
    config.nbTypeDefaults = 0;

    auto graphId = ZL_Clustering_registerGraph(compressor.get(), &config, successors, 1);
    compressor.selectStartingGraph(graphId);
    compressor.setParameter(openzl::CParam::CompressionLevel, 1);
    compressor.setParameter(openzl::CParam::MinStreamSize, 0);
    return compressor;
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


struct StructuredJNIRefs {
    jclass inputsClass = nullptr;
    jfieldID fieldIdsField = nullptr;
    jfieldID fieldTypesField = nullptr;
    jfieldID fieldLengthsField = nullptr;
    jfieldID fieldsField = nullptr;
    jfieldID fieldCountField = nullptr;

    jclass fieldStreamClass = nullptr;
    jfieldID fieldStreamTagField = nullptr;
    jfieldID fieldStreamKindField = nullptr;
    jfieldID fieldStreamDataField = nullptr;
    jfieldID fieldStreamLengthsField = nullptr;

    jclass byteAccumulatorClass = nullptr;
    jfieldID dataField = nullptr;
    jfieldID sizeField = nullptr;
};

StructuredJNIRefs& structuredJniRefs()
{
    static StructuredJNIRefs refs;
    return refs;
}

uint32_t structuredFNV(uint32_t hash, uint32_t value)
{
    return (hash ^ value) * kStructuredFnvPrime;
}

uint32_t structuredExtendPathHash(uint32_t pathHash, uint32_t fieldNumber)
{
    uint32_t hash = pathHash;
    hash = structuredFNV(hash, fieldNumber & 0xFFu);
    hash = structuredFNV(hash, (fieldNumber >> 8) & 0xFFu);
    hash = structuredFNV(hash, (fieldNumber >> 16) & 0xFFu);
    hash = structuredFNV(hash, (fieldNumber >> 24) & 0xFFu);
    return hash;
}

int structuredFieldTag(uint32_t pathHash, uint32_t fieldNumber)
{
    uint32_t tag = structuredExtendPathHash(pathHash, fieldNumber) & 0x7fffffffu;
    return tag == 0 ? 1 : static_cast<int>(tag);
}

bool ensureStructuredJniRefs(JNIEnv* env)
{
    static std::mutex mutex;
    std::lock_guard<std::mutex> lock(mutex);
    auto& refs = structuredJniRefs();
    if (refs.inputsClass != nullptr) {
        return true;
    }

    jclass localInputs = env->FindClass("io/github/hybledav/quarkus/grpc/OpenZLStructuredProtoInputs");
    if (localInputs == nullptr) {
        return false;
    }
    refs.inputsClass = static_cast<jclass>(env->NewGlobalRef(localInputs));
    env->DeleteLocalRef(localInputs);
    if (refs.inputsClass == nullptr) {
        return false;
    }
    refs.fieldIdsField = env->GetFieldID(refs.inputsClass, "fieldIds", "Lio/github/hybledav/quarkus/grpc/OpenZLStructuredProtoInputs$ByteAccumulator;");
    refs.fieldTypesField = env->GetFieldID(refs.inputsClass, "fieldTypes", "Lio/github/hybledav/quarkus/grpc/OpenZLStructuredProtoInputs$ByteAccumulator;");
    refs.fieldLengthsField = env->GetFieldID(refs.inputsClass, "fieldLengths", "Lio/github/hybledav/quarkus/grpc/OpenZLStructuredProtoInputs$ByteAccumulator;");
    refs.fieldsField = env->GetFieldID(refs.inputsClass, "fields", "[Lio/github/hybledav/quarkus/grpc/OpenZLStructuredProtoInputs$FieldStream;");
    refs.fieldCountField = env->GetFieldID(refs.inputsClass, "fieldCount", "I");
    if (refs.fieldIdsField == nullptr || refs.fieldTypesField == nullptr || refs.fieldLengthsField == nullptr || refs.fieldsField == nullptr || refs.fieldCountField == nullptr) {
        return false;
    }

    jclass localFieldStream = env->FindClass("io/github/hybledav/quarkus/grpc/OpenZLStructuredProtoInputs$FieldStream");
    if (localFieldStream == nullptr) {
        return false;
    }
    refs.fieldStreamClass = static_cast<jclass>(env->NewGlobalRef(localFieldStream));
    env->DeleteLocalRef(localFieldStream);
    if (refs.fieldStreamClass == nullptr) {
        return false;
    }
    refs.fieldStreamTagField = env->GetFieldID(refs.fieldStreamClass, "tag", "I");
    refs.fieldStreamKindField = env->GetFieldID(refs.fieldStreamClass, "kind", "I");
    refs.fieldStreamDataField = env->GetFieldID(refs.fieldStreamClass, "data", "Lio/github/hybledav/quarkus/grpc/OpenZLStructuredProtoInputs$ByteAccumulator;");
    refs.fieldStreamLengthsField = env->GetFieldID(refs.fieldStreamClass, "lengths", "Lio/github/hybledav/quarkus/grpc/OpenZLStructuredProtoInputs$ByteAccumulator;");
    if (refs.fieldStreamTagField == nullptr || refs.fieldStreamKindField == nullptr || refs.fieldStreamDataField == nullptr || refs.fieldStreamLengthsField == nullptr) {
        return false;
    }

    jclass localAccumulator = env->FindClass("io/github/hybledav/quarkus/grpc/OpenZLStructuredProtoInputs$ByteAccumulator");
    if (localAccumulator == nullptr) {
        return false;
    }
    refs.byteAccumulatorClass = static_cast<jclass>(env->NewGlobalRef(localAccumulator));
    env->DeleteLocalRef(localAccumulator);
    if (refs.byteAccumulatorClass == nullptr) {
        return false;
    }
    refs.dataField = env->GetFieldID(refs.byteAccumulatorClass, "data", "Ljava/nio/ByteBuffer;");
    refs.sizeField = env->GetFieldID(refs.byteAccumulatorClass, "size", "I");
    return refs.dataField != nullptr && refs.sizeField != nullptr;
}

struct PinnedAccumulator {
    jobject accumulator = nullptr;
    jobject directBuffer = nullptr;
    const unsigned char* address = nullptr;
    size_t size = 0;
};

void releasePinnedAccumulator(JNIEnv* env, PinnedAccumulator& pinned)
{
    if (pinned.directBuffer != nullptr) {
        env->DeleteLocalRef(pinned.directBuffer);
        pinned.directBuffer = nullptr;
    }
    if (pinned.accumulator != nullptr) {
        env->DeleteLocalRef(pinned.accumulator);
        pinned.accumulator = nullptr;
    }
    pinned.address = nullptr;
    pinned.size = 0;
}

bool pinAccumulator(JNIEnv* env, jobject accumulator, PinnedAccumulator& pinned, bool allowNull)
{
    if (accumulator == nullptr) {
        if (allowNull) {
            return true;
        }
        throwIllegalState(env, "Structured protobuf accumulator must not be null");
        return false;
    }
    auto& refs = structuredJniRefs();
    pinned.accumulator = accumulator;
    jobject data = env->GetObjectField(accumulator, refs.dataField);
    if (data == nullptr) {
        return false;
    }
    pinned.directBuffer = data;
    jint size = env->GetIntField(accumulator, refs.sizeField);
    if (size < 0) {
        throwIllegalState(env, "Structured protobuf input stream size must be non-negative");
        return false;
    }
    pinned.size = static_cast<size_t>(size);
    if (pinned.size == 0) {
        pinned.address = nullptr;
        return true;
    }
    void* address = env->GetDirectBufferAddress(data);
    if (address == nullptr) {
        throwIllegalState(env, "Structured protobuf input buffer must be direct");
        return false;
    }
    pinned.address = static_cast<const unsigned char*>(address);
    return true;
}

struct StructuredPinnedField {
    jobject fieldStream = nullptr;
    int tag = 0;
    int kind = 0;
    PinnedAccumulator data;
    PinnedAccumulator lengths;
};

struct StructuredPinnedBuffers {
    PinnedAccumulator fieldIds;
    PinnedAccumulator fieldTypes;
    PinnedAccumulator fieldLengths;
    jobjectArray fields = nullptr;
    std::vector<StructuredPinnedField> valueStreams;
};

void releaseStructuredPinnedBuffers(JNIEnv* env, StructuredPinnedBuffers& buffers)
{
    releasePinnedAccumulator(env, buffers.fieldIds);
    releasePinnedAccumulator(env, buffers.fieldTypes);
    releasePinnedAccumulator(env, buffers.fieldLengths);
    for (auto& stream : buffers.valueStreams) {
        releasePinnedAccumulator(env, stream.data);
        releasePinnedAccumulator(env, stream.lengths);
        if (stream.fieldStream != nullptr) {
            env->DeleteLocalRef(stream.fieldStream);
            stream.fieldStream = nullptr;
        }
    }
    buffers.valueStreams.clear();
    if (buffers.fields != nullptr) {
        env->DeleteLocalRef(buffers.fields);
        buffers.fields = nullptr;
    }
}

bool pinStructuredBuffers(JNIEnv* env, jobject inputs, StructuredPinnedBuffers& buffers)
{
    if (!ensureStructuredJniRefs(env)) {
        return false;
    }
    auto& refs = structuredJniRefs();

    jobject fieldIds = env->GetObjectField(inputs, refs.fieldIdsField);
    if (!pinAccumulator(env, fieldIds, buffers.fieldIds, false)) {
        return false;
    }
    jobject fieldTypes = env->GetObjectField(inputs, refs.fieldTypesField);
    if (!pinAccumulator(env, fieldTypes, buffers.fieldTypes, false)) {
        return false;
    }
    jobject fieldLengths = env->GetObjectField(inputs, refs.fieldLengthsField);
    if (!pinAccumulator(env, fieldLengths, buffers.fieldLengths, false)) {
        return false;
    }

    buffers.fields = static_cast<jobjectArray>(env->GetObjectField(inputs, refs.fieldsField));
    if (buffers.fields == nullptr) {
        return !env->ExceptionCheck();
    }
    jint fieldCount = env->GetIntField(inputs, refs.fieldCountField);
    if (fieldCount < 0) {
        throwIllegalState(env, "Structured protobuf fieldCount must be non-negative");
        return false;
    }
    buffers.valueStreams.reserve(static_cast<size_t>(fieldCount));
    for (jsize i = 0; i < fieldCount; ++i) {
        jobject fieldStream = env->GetObjectArrayElement(buffers.fields, i);
        if (fieldStream == nullptr) {
            return false;
        }
        StructuredPinnedField pinned;
        pinned.fieldStream = fieldStream;
        pinned.tag = env->GetIntField(fieldStream, refs.fieldStreamTagField);
        pinned.kind = env->GetIntField(fieldStream, refs.fieldStreamKindField);
        jobject data = env->GetObjectField(fieldStream, refs.fieldStreamDataField);
        if (!pinAccumulator(env, data, pinned.data, false)) {
            return false;
        }
        jobject lengths = env->GetObjectField(fieldStream, refs.fieldStreamLengthsField);
        if (!pinAccumulator(env, lengths, pinned.lengths, true)) {
            return false;
        }
        buffers.valueStreams.emplace_back(std::move(pinned));
    }
    return true;
}

void configureStructuredCCtx(openzl::CCtx& cctx)
{
    cctx.setParameter(openzl::CParam::StickyParameters, 1);
    cctx.setParameter(openzl::CParam::CompressionLevel, 1);
    cctx.setParameter(openzl::CParam::FormatVersion, ZL_MAX_FORMAT_VERSION);
}

struct StructuredSerializerCacheEntry {
    openzl::Compressor compressor;
    openzl::CCtx cctx;
    bool initialized = false;

    void initialize(const std::string& type)
    {
        if (initialized) {
            return;
        }
        compressor = createStructuredCompressorForType(type);
        configureStructuredCCtx(cctx);
        cctx.refCompressor(compressor);
        initialized = true;
    }
};

StructuredSerializerCacheEntry& structuredSerializerEntryForType(const std::string& type)
{
    thread_local std::unordered_map<std::string, StructuredSerializerCacheEntry> cache;
    auto [it, inserted] = cache.try_emplace(type);
    (void)inserted;
    it->second.initialize(type);
    return it->second;
}

struct ExplicitStructuredCacheEntry {
    openzl::Compressor compressor;
    openzl::CCtx cctx;

    explicit ExplicitStructuredCacheEntry(const std::string& serialized)
    {
        compressor.deserialize(serialized);
        configureStructuredCCtx(cctx);
        cctx.refCompressor(compressor);
    }
};

ExplicitStructuredCacheEntry& explicitStructuredEntryForCompressor(
        const std::string& type,
        const std::string& serialized)
{
    std::string key;
    key.reserve(type.size() + 1 + serialized.size());
    key.append(type);
    key.push_back('\0');
    key.append(serialized);

    thread_local std::unordered_map<std::string, std::unique_ptr<ExplicitStructuredCacheEntry>> cache;
    auto it = cache.find(key);
    if (it == cache.end()) {
        auto entry = std::make_unique<ExplicitStructuredCacheEntry>(serialized);
        it = cache.emplace(std::move(key), std::move(entry)).first;
    }
    return *it->second;
}

std::vector<openzl::Input> buildStructuredInputs(const StructuredPinnedBuffers& buffers)
{
    thread_local std::vector<uint32_t> fieldIdsScratch;
    thread_local std::vector<uint32_t> fieldTypesScratch;
    thread_local std::vector<uint32_t> fieldLengthsScratch;
    std::vector<openzl::Input> inputs;
    inputs.reserve(3 + buffers.valueStreams.size());

    auto addControl = [&inputs](const PinnedAccumulator& pinned,
                                int tag,
                                uint32_t controlKind,
                                std::vector<uint32_t>& scratch) {
        scratch.clear();
        scratch.reserve(pinned.size / 4 + 2);
        scratch.push_back(kStructuredControlMagic);
        scratch.push_back(controlKind);
        if (pinned.size != 0) {
            auto const* values = reinterpret_cast<const uint32_t*>(pinned.address);
            scratch.insert(scratch.end(), values, values + (pinned.size / 4));
        }
        auto input = openzl::Input::refNumeric(scratch.data(), 4, scratch.size());
        input.setIntMetadata(ZL_CLUSTERING_TAG_METADATA_ID, tag);
        inputs.emplace_back(std::move(input));
    };
    addControl(buffers.fieldIds, kStructuredFieldIdTag, kStructuredControlFieldIds, fieldIdsScratch);
    addControl(buffers.fieldTypes, kStructuredFieldTypeTag, kStructuredControlFieldTypes, fieldTypesScratch);
    addControl(buffers.fieldLengths, kStructuredFieldLengthTag, kStructuredControlFieldLengths, fieldLengthsScratch);

    for (const auto& stream : buffers.valueStreams) {
        openzl::Input input = [&stream]() {
            switch (stream.kind) {
                case 1:
                case 3:
                case 6:
                case 8:
                    return openzl::Input::refNumeric(stream.data.address, 4, stream.data.size / 4);
                case 2:
                case 4:
                case 5:
                    return openzl::Input::refNumeric(stream.data.address, 8, stream.data.size / 8);
                case 7:
                    return openzl::Input::refNumeric(stream.data.address, 1, stream.data.size);
                case 9:
                    return openzl::Input::refString(
                            stream.data.address,
                            stream.data.size,
                            reinterpret_cast<const uint32_t*>(stream.lengths.address),
                            stream.lengths.size / 4);
                default:
                    throw std::runtime_error("Unsupported structured protobuf logical field kind");
            }
        }();
        input.setIntMetadata(ZL_CLUSTERING_TAG_METADATA_ID, stream.tag);
        inputs.emplace_back(std::move(input));
    }
    return inputs;
}

struct StructuredSampleFieldStorage {
    int tag = 0;
    int kind = 0;
    std::vector<uint32_t> values32;
    std::vector<uint64_t> values64;
    std::string bytes;
    std::vector<uint32_t> lengths;
};

struct StructuredSampleStorage {
    std::vector<uint32_t> fieldIds;
    std::vector<uint32_t> fieldTypes;
    std::vector<uint32_t> fieldLengths;
    std::vector<StructuredSampleFieldStorage> valueStreams;
};

uint32_t readStructuredSampleUInt32(const unsigned char*& cursor, const unsigned char* end)
{
    if (static_cast<size_t>(end - cursor) < sizeof(uint32_t)) {
        throw std::runtime_error("Structured protobuf sample is truncated");
    }
    uint32_t value = static_cast<uint32_t>(cursor[0])
            | (static_cast<uint32_t>(cursor[1]) << 8)
            | (static_cast<uint32_t>(cursor[2]) << 16)
            | (static_cast<uint32_t>(cursor[3]) << 24);
    cursor += sizeof(uint32_t);
    return value;
}

uint64_t readStructuredSampleUInt64(const unsigned char*& cursor, const unsigned char* end)
{
    uint64_t lo = readStructuredSampleUInt32(cursor, end);
    uint64_t hi = readStructuredSampleUInt32(cursor, end);
    return lo | (hi << 32);
}

void requireStructuredSampleBytes(const unsigned char*& cursor, const unsigned char* end, size_t size)
{
    if (size > static_cast<size_t>(end - cursor)) {
        throw std::runtime_error("Structured protobuf sample is truncated");
    }
}

std::vector<uint32_t> readStructuredSampleUInt32Vector(const unsigned char*& cursor, const unsigned char* end, size_t byteSize)
{
    if (byteSize % sizeof(uint32_t) != 0) {
        throw std::runtime_error("Structured protobuf control stream is misaligned");
    }
    requireStructuredSampleBytes(cursor, end, byteSize);
    std::vector<uint32_t> out;
    out.reserve(byteSize / sizeof(uint32_t));
    for (size_t i = 0; i < byteSize; i += sizeof(uint32_t)) {
        out.push_back(readStructuredSampleUInt32(cursor, end));
    }
    return out;
}

StructuredSampleStorage parseStructuredSample(const std::string& sample)
{
    const auto* cursor = reinterpret_cast<const unsigned char*>(sample.data());
    const auto* end = cursor + sample.size();
    StructuredSampleStorage storage;

    uint32_t fieldIdsSize = readStructuredSampleUInt32(cursor, end);
    uint32_t fieldTypesSize = readStructuredSampleUInt32(cursor, end);
    uint32_t fieldLengthsSize = readStructuredSampleUInt32(cursor, end);
    uint32_t fieldCount = readStructuredSampleUInt32(cursor, end);

    storage.fieldIds = readStructuredSampleUInt32Vector(cursor, end, fieldIdsSize);
    storage.fieldTypes = readStructuredSampleUInt32Vector(cursor, end, fieldTypesSize);
    storage.fieldLengths = readStructuredSampleUInt32Vector(cursor, end, fieldLengthsSize);
    storage.valueStreams.reserve(fieldCount);

    for (uint32_t i = 0; i < fieldCount; ++i) {
        StructuredSampleFieldStorage field;
        field.tag = static_cast<int>(readStructuredSampleUInt32(cursor, end));
        field.kind = static_cast<int>(readStructuredSampleUInt32(cursor, end));
        uint32_t dataSize = readStructuredSampleUInt32(cursor, end);
        uint32_t lengthsSize = readStructuredSampleUInt32(cursor, end);
        requireStructuredSampleBytes(cursor, end, dataSize + lengthsSize);

        switch (field.kind) {
            case 1:
            case 3:
            case 6:
            case 8:
                field.values32 = readStructuredSampleUInt32Vector(cursor, end, dataSize);
                break;
            case 2:
            case 4:
            case 5: {
                if (dataSize % sizeof(uint64_t) != 0) {
                    throw std::runtime_error("Structured protobuf 64-bit stream is misaligned");
                }
                field.values64.reserve(dataSize / sizeof(uint64_t));
                for (size_t consumed = 0; consumed < dataSize; consumed += sizeof(uint64_t)) {
                    field.values64.push_back(readStructuredSampleUInt64(cursor, end));
                }
                break;
            }
            case 7:
                field.bytes.assign(reinterpret_cast<const char*>(cursor), dataSize);
                cursor += dataSize;
                break;
            case 9:
                field.bytes.assign(reinterpret_cast<const char*>(cursor), dataSize);
                cursor += dataSize;
                field.lengths = readStructuredSampleUInt32Vector(cursor, end, lengthsSize);
                break;
            default:
                throw std::runtime_error("Unsupported structured protobuf logical field kind");
        }
        if (field.kind != 9 && lengthsSize != 0) {
            throw std::runtime_error("Structured protobuf non-string field has lengths payload");
        }
        storage.valueStreams.emplace_back(std::move(field));
    }

    if (cursor != end) {
        throw std::runtime_error("Structured protobuf sample contains trailing bytes");
    }
    return storage;
}

std::vector<openzl::Input> buildStructuredInputs(const StructuredSampleStorage& storage)
{
    thread_local std::vector<uint32_t> fieldIdsScratch;
    thread_local std::vector<uint32_t> fieldTypesScratch;
    thread_local std::vector<uint32_t> fieldLengthsScratch;
    std::vector<openzl::Input> inputs;
    inputs.reserve(3 + storage.valueStreams.size());

    auto addControl = [&inputs](const std::vector<uint32_t>& values,
                                int tag,
                                uint32_t controlKind,
                                std::vector<uint32_t>& scratch) {
        scratch.clear();
        scratch.reserve(values.size() + 2);
        scratch.push_back(kStructuredControlMagic);
        scratch.push_back(controlKind);
        scratch.insert(scratch.end(), values.begin(), values.end());
        auto input = openzl::Input::refNumeric(scratch.data(), 4, scratch.size());
        input.setIntMetadata(ZL_CLUSTERING_TAG_METADATA_ID, tag);
        inputs.emplace_back(std::move(input));
    };
    addControl(storage.fieldIds, kStructuredFieldIdTag, kStructuredControlFieldIds, fieldIdsScratch);
    addControl(storage.fieldTypes, kStructuredFieldTypeTag, kStructuredControlFieldTypes, fieldTypesScratch);
    addControl(storage.fieldLengths, kStructuredFieldLengthTag, kStructuredControlFieldLengths, fieldLengthsScratch);

    for (const auto& field : storage.valueStreams) {
        openzl::Input input = [&field]() {
            switch (field.kind) {
                case 1:
                case 3:
                case 6:
                case 8:
                    return openzl::Input::refNumeric(field.values32.data(), 4, field.values32.size());
                case 2:
                case 4:
                case 5:
                    return openzl::Input::refNumeric(field.values64.data(), 8, field.values64.size());
                case 7:
                    return openzl::Input::refNumeric(field.bytes.data(), 1, field.bytes.size());
                case 9:
                    return openzl::Input::refString(field.bytes.data(), field.bytes.size(), field.lengths.data(), field.lengths.size());
                default:
                    throw std::runtime_error("Unsupported structured protobuf logical field kind");
            }
        }();
        input.setIntMetadata(ZL_CLUSTERING_TAG_METADATA_ID, field.tag);
        inputs.emplace_back(std::move(input));
    }
    return inputs;
}

jbyteArray compressStructuredPayload(
        JNIEnv* env,
        jobject structuredInputs,
        jbyteArray compressorBytes,
        const std::string& typeName)
{
    StructuredPinnedBuffers buffers;
    const auto pinStart = std::chrono::steady_clock::now();
    if (!pinStructuredBuffers(env, structuredInputs, buffers)) {
        releaseStructuredPinnedBuffers(env, buffers);
        return nullptr;
    }
    const auto pinEnd = std::chrono::steady_clock::now();

    try {
        const auto buildStart = std::chrono::steady_clock::now();
        auto inputs = buildStructuredInputs(buffers);
        const auto buildEnd = std::chrono::steady_clock::now();
        std::string result;
        const auto compressStart = std::chrono::steady_clock::now();
        if (compressorBytes != nullptr) {
            std::string serialized = copyArray(env, compressorBytes);
            if (env->ExceptionCheck()) {
                releaseStructuredPinnedBuffers(env, buffers);
                return nullptr;
            }
            auto& entry = explicitStructuredEntryForCompressor(typeName, serialized);
            result = entry.cctx.compress(inputs);
        } else {
            auto& structuredEntry = structuredSerializerEntryForType(typeName);
            result = structuredEntry.cctx.compress(inputs);
        }
        const auto compressEnd = std::chrono::steady_clock::now();
        releaseStructuredPinnedBuffers(env, buffers);
        const auto outStart = std::chrono::steady_clock::now();
        jbyteArray out = makeByteArray(env, result);
        const auto outEnd = std::chrono::steady_clock::now();
        recordStructuredPerf(
                static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(pinEnd - pinStart).count()),
                static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(buildEnd - buildStart).count()),
                static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(compressEnd - compressStart).count()),
                static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(outEnd - outStart).count()));
        return out;
    } catch (const std::exception& ex) {
        releaseStructuredPinnedBuffers(env, buffers);
        throwIllegalState(env, ex.what());
        return nullptr;
    }
}

struct OwnedStructuredOutput {
    openzl::Type type = openzl::Type::Serial;
    size_t eltWidth = 0;
    size_t numElts = 0;
    std::vector<char> bytes;
    std::vector<uint32_t> stringLengths;
    bool hasTag = false;
    int tag = 0;
};

OwnedStructuredOutput copyStructuredOutput(const openzl::Output& output)
{
    OwnedStructuredOutput owned;
    owned.type = output.type();
    owned.bytes.resize(output.contentSize());
    if (!owned.bytes.empty()) {
        std::memcpy(owned.bytes.data(), output.ptr(), owned.bytes.size());
    }
    auto maybeTag = output.getIntMetadata(ZL_CLUSTERING_TAG_METADATA_ID);
    owned.hasTag = maybeTag.has_value();
    owned.tag = maybeTag.has_value() ? *maybeTag : 0;
    if (owned.type == openzl::Type::String) {
        owned.numElts = output.numElts();
        auto const* lengths = output.stringLens();
        owned.stringLengths.assign(lengths, lengths + owned.numElts);
    } else {
        owned.eltWidth = output.eltWidth();
        owned.numElts = output.numElts();
    }
    return owned;
}

struct StructuredValueCursor {
    OwnedStructuredOutput output;
    openzl::protobuf::StringReader data;
    size_t stringIndex = 0;

    explicit StructuredValueCursor(OwnedStructuredOutput&& value)
            : output(std::move(value))
            , data(std::string_view(output.bytes.data(), output.bytes.size()))
    {
    }
};

struct StructuredDecodedFrame {
    std::unique_ptr<StructuredValueCursor> fieldIds;
    std::unique_ptr<StructuredValueCursor> fieldTypes;
    std::unique_ptr<StructuredValueCursor> fieldLengths;
    std::unordered_map<int, std::unique_ptr<StructuredValueCursor>> values;
    std::deque<std::unique_ptr<StructuredValueCursor>> orderedValues;
};

void stripStructuredControlHeader(StructuredValueCursor& cursor)
{
    uint32_t magic = 0;
    uint32_t kind = 0;
    cursor.data.readLE(magic);
    cursor.data.readLE(kind);
    if (magic != kStructuredControlMagic) {
        throw std::runtime_error("Structured control stream missing magic header");
    }
}

bool tryAssignStructuredControl(StructuredDecodedFrame& frame, std::unique_ptr<StructuredValueCursor>& cursor)
{
    if (cursor == nullptr || cursor->output.bytes.size() < 2 * sizeof(uint32_t)) {
        return false;
    }
    auto const* words = reinterpret_cast<const uint32_t*>(cursor->output.bytes.data());
    if (words[0] != kStructuredControlMagic) {
        return false;
    }
    stripStructuredControlHeader(*cursor);
    switch (words[1]) {
        case kStructuredControlFieldIds:
            frame.fieldIds = std::move(cursor);
            return true;
        case kStructuredControlFieldTypes:
            frame.fieldTypes = std::move(cursor);
            return true;
        case kStructuredControlFieldLengths:
            frame.fieldLengths = std::move(cursor);
            return true;
        default:
            throw std::runtime_error("Unknown structured control stream kind");
    }
}

template <typename T>
void readLE(openzl::protobuf::StringReader& reader, T& value)
{
    reader.readLE(value);
}

uint32_t readLengthControl(StructuredDecodedFrame& frame)
{
    uint32_t value = 0;
    readLE(frame.fieldLengths->data, value);
    return value;
}

std::string readStringValue(StructuredValueCursor& cursor, uint32_t expectedLength)
{
    if (cursor.output.type != openzl::Type::String) {
        throw std::runtime_error("Structured field stream is not string-typed");
    }
    uint32_t actualLength = cursor.output.stringLengths[cursor.stringIndex++];
    if (actualLength != expectedLength) {
        throw std::runtime_error("Structured field stream length mismatch");
    }
    std::string value;
    cursor.data.read(value, actualLength);
    return value;
}

template <openzl::protobuf::InputType Type>
size_t readStructuredValue(
        typename openzl::protobuf::InputTraits<Type>::type& value,
        StructuredValueCursor& cursor,
        StructuredDecodedFrame& frame)
{
    using T = typename openzl::protobuf::InputTraits<Type>::type;
    if constexpr (std::is_same_v<T, std::string>) {
        uint32_t len = readLengthControl(frame);
        value = readStringValue(cursor, len);
        return static_cast<size_t>(len) + sizeof(uint32_t);
    } else {
        cursor.data.readLE(value);
        return sizeof(T);
    }
}

struct ReadStructuredField {
    template <openzl::protobuf::InputType Type>
    size_t operator()(
            const openzl::protobuf::Reflection* ref,
            openzl::protobuf::Message& message,
            const openzl::protobuf::FieldDescriptor* field,
            StructuredValueCursor& cursor,
            StructuredDecodedFrame& frame)
    {
        using T = typename openzl::protobuf::InputTraits<Type>::type;
        size_t total = 0;
        if (!field->is_repeated()) {
            T value{};
            total += readStructuredValue<Type>(value, cursor, frame);
            (ref->*openzl::protobuf::InputTraits<Type>::Set)(&message, field, std::move(value));
        } else {
            uint32_t count = readLengthControl(frame);
            total += sizeof(uint32_t);
            auto repeated = ref->GetMutableRepeatedFieldRef<T>(&message, field);
            for (uint32_t i = 0; i < count; ++i) {
                T value{};
                total += readStructuredValue<Type>(value, cursor, frame);
                repeated.Add(std::move(value));
            }
        }
        return total;
    }
};

StructuredValueCursor& requireStructuredCursor(StructuredDecodedFrame& frame, int tag)
{
    auto it = frame.values.find(tag);
    if (it != frame.values.end()) {
        return *it->second;
    }
    if (frame.orderedValues.empty()) {
        throw std::runtime_error("Missing structured field stream for tag " + std::to_string(tag));
    }
    auto cursor = std::move(frame.orderedValues.front());
    frame.orderedValues.pop_front();
    auto inserted = frame.values.emplace(tag, std::move(cursor));
    return *inserted.first->second;
}

size_t readStructuredMessage(
        openzl::protobuf::Message& message,
        StructuredDecodedFrame& frame,
        uint32_t pathHash)
{
    const auto ref = message.GetReflection();
    const auto desc = message.GetDescriptor();
    size_t total = 0;
    while (!frame.fieldTypes->data.atEnd()) {
        uint32_t fieldType = 0;
        readLE(frame.fieldTypes->data, fieldType);
        total += sizeof(uint32_t);
        if (fieldType == openzl::protobuf::kStop) {
            return total;
        }

        uint32_t fieldId = 0;
        readLE(frame.fieldIds->data, fieldId);
        total += sizeof(uint32_t);

        auto* field = desc->FindFieldByNumber(static_cast<int>(fieldId));
        if (field == nullptr) {
            throw std::runtime_error("Unknown field id in structured protobuf payload");
        }
        if (fieldType != static_cast<uint32_t>(field->cpp_type())) {
            throw std::runtime_error("Structured protobuf field type mismatch");
        }

        if (fieldType == google::protobuf::FieldDescriptor::CPPTYPE_MESSAGE) {
            uint32_t childHash = structuredExtendPathHash(pathHash, fieldId);
            if (!field->is_repeated()) {
                auto* nested = ref->MutableMessage(&message, field);
                total += readStructuredMessage(*nested, frame, childHash);
            } else {
                uint32_t count = readLengthControl(frame);
                total += sizeof(uint32_t);
                for (uint32_t i = 0; i < count; ++i) {
                    auto* nested = ref->AddMessage(&message, field);
                    total += readStructuredMessage(*nested, frame, childHash);
                }
            }
            continue;
        }

        int tag = structuredFieldTag(pathHash, fieldId);
        auto& cursor = requireStructuredCursor(frame, tag);
        total += openzl::protobuf::call(
                openzl::protobuf::CPPTypeToInputType[fieldType],
                ReadStructuredField{},
                ref,
                message,
                field,
                cursor,
                frame);
    }
    return total;
}

StructuredDecodedFrame decodeStructuredFrame(const std::string& compressed)
{
    struct OutputDebugInfo {
        int type = 0;
        size_t width = 0;
        size_t elts = 0;
        size_t size = 0;
        bool hasTag = false;
        int tag = 0;
        bool hasWords = false;
        uint32_t word0 = 0;
        uint32_t word1 = 0;
    };

    openzl::DCtx dctx;
    auto outputs = dctx.decompress(compressed);
    std::vector<OutputDebugInfo> debugOutputs;
    debugOutputs.reserve(outputs.size());
    for (auto const& output : outputs) {
        OutputDebugInfo info;
        auto type = output.type();
        info.type = static_cast<int>(type);
        info.size = output.contentSize();
        if (type != openzl::Type::String) {
            info.width = output.eltWidth();
            info.elts = output.numElts();
        }
        auto tag = output.getIntMetadata(ZL_CLUSTERING_TAG_METADATA_ID);
        info.hasTag = tag.has_value();
        info.tag = tag.has_value() ? *tag : 0;
        if (type == openzl::Type::Numeric && output.eltWidth() == 4 && output.numElts() >= 2) {
            auto const* words = static_cast<const uint32_t*>(output.ptr());
            info.hasWords = true;
            info.word0 = words[0];
            info.word1 = words[1];
        }
        debugOutputs.push_back(info);
    }
    StructuredDecodedFrame frame;
    for (auto& output : outputs) {
        auto owned = copyStructuredOutput(output);
        if (!owned.hasTag && owned.bytes.size() >= 2 * sizeof(uint32_t)) {
            auto const* words = reinterpret_cast<const uint32_t*>(owned.bytes.data());
            if (words[0] == kStructuredControlMagic) {
                auto cursor = std::make_unique<StructuredValueCursor>(std::move(owned));
                stripStructuredControlHeader(*cursor);
                switch (words[1]) {
                    case kStructuredControlFieldIds:
                        frame.fieldIds = std::move(cursor);
                        break;
                    case kStructuredControlFieldTypes:
                        frame.fieldTypes = std::move(cursor);
                        break;
                    case kStructuredControlFieldLengths:
                        frame.fieldLengths = std::move(cursor);
                        break;
                    default:
                        throw std::runtime_error("Unknown structured control stream kind");
                }
                continue;
            }
        }

        auto cursor = std::make_unique<StructuredValueCursor>(std::move(owned));
        if (!cursor->output.hasTag) {
            if (tryAssignStructuredControl(frame, cursor)) {
                continue;
            }
            frame.orderedValues.emplace_back(std::move(cursor));
            continue;
        }
        int tag = cursor->output.tag;
        if (tag == kStructuredFieldIdTag) {
            stripStructuredControlHeader(*cursor);
            frame.fieldIds = std::move(cursor);
        } else if (tag == kStructuredFieldTypeTag) {
            stripStructuredControlHeader(*cursor);
            frame.fieldTypes = std::move(cursor);
        } else if (tag == kStructuredFieldLengthTag) {
            stripStructuredControlHeader(*cursor);
            frame.fieldLengths = std::move(cursor);
        } else {
            frame.values.emplace(tag, std::move(cursor));
        }
    }
    for (auto it = frame.orderedValues.begin(); it != frame.orderedValues.end();) {
        if (tryAssignStructuredControl(frame, *it)) {
            it = frame.orderedValues.erase(it);
        } else {
            ++it;
        }
    }
    if (!frame.fieldIds || !frame.fieldTypes || !frame.fieldLengths) {
        std::string details = "Structured frame missing control streams outputs=" + std::to_string(outputs.size())
                + " ordered=" + std::to_string(frame.orderedValues.size())
                + " ids=" + std::to_string(frame.fieldIds ? 1 : 0)
                + " types=" + std::to_string(frame.fieldTypes ? 1 : 0)
                + " lengths=" + std::to_string(frame.fieldLengths ? 1 : 0);
        size_t limit = std::min<size_t>(debugOutputs.size(), 8);
        for (size_t i = 0; i < limit; ++i) {
            auto const& output = debugOutputs[i];
            details += " | out[" + std::to_string(i)
                    + "] type=" + std::to_string(output.type)
                    + " width=" + std::to_string(output.width)
                    + " elts=" + std::to_string(output.elts)
                    + " size=" + std::to_string(output.size)
                    + " tag=" + std::to_string(output.tag)
                    + " present=" + std::to_string(output.hasTag ? 1 : 0);
            if (output.hasWords) {
                char wordsBuf[64];
                std::snprintf(wordsBuf, sizeof(wordsBuf), " first=[0x%08x,0x%08x]", output.word0, output.word1);
                details += wordsBuf;
            }
        }
        throw std::runtime_error(details);
    }
    return frame;
}

jbyteArray decompressStructuredPayload(
        JNIEnv* env,
        jbyteArray payload,
        const std::string& typeName)
{
    try {
        std::string compressed = copyArray(env, payload);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        auto frame = decodeStructuredFrame(compressed);
        google::protobuf::Message& message = reusableMessage(typeName);
        message.Clear();
        readStructuredMessage(message, frame, kStructuredRootPathHash);
        std::string proto;
        if (!message.SerializeToString(&proto)) {
            throw std::runtime_error("Failed to serialize structured protobuf message");
        }
        return makeByteArray(env, proto);
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
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

extern "C" JNIEXPORT jlongArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_structuredProfileNative(
        JNIEnv* env,
        jclass)
{
    auto& counters = structuredPerfCounters();
    jlong values[6] = {
            structuredPerfEnabled() ? 1 : 0,
            static_cast<jlong>(counters.pinNs.load(std::memory_order_relaxed)),
            static_cast<jlong>(counters.buildNs.load(std::memory_order_relaxed)),
            static_cast<jlong>(counters.compressNs.load(std::memory_order_relaxed)),
            static_cast<jlong>(counters.outNs.load(std::memory_order_relaxed)),
            static_cast<jlong>(counters.calls.load(std::memory_order_relaxed))};
    jlongArray out = env->NewLongArray(6);
    if (out == nullptr) {
        throwNew(env, JniRefs().outOfMemoryError, "Unable to allocate structured profile snapshot");
        return nullptr;
    }
    env->SetLongArrayRegion(out, 0, 6, values);
    return out;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLStructuredProtoBridge_compressStructuredNative(
        JNIEnv* env,
        jclass,
        jobject structuredInputs,
        jbyteArray compressorBytes,
        jstring messageType)
{
    if (structuredInputs == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "inputs");
        return nullptr;
    }

    std::string typeName = requireMessageType(env, messageType);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    if (!DescriptorRegistry::instance().hasType(typeName)) {
        throwIllegalArgument(env, "Unknown protobuf message type: " + typeName);
        return nullptr;
    }

    return compressStructuredPayload(env, structuredInputs, compressorBytes, typeName);
}

extern "C" JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLStructuredProtoBridge_compressStructuredIntoNative(
        JNIEnv* env,
        jclass,
        jobject structuredInputs,
        jbyteArray compressorBytes,
        jstring messageType,
        jobject outputBuffer,
        jint outputPosition,
        jint outputLength)
{
    if (structuredInputs == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "inputs");
        return 0;
    }
    if (outputBuffer == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "output");
        return 0;
    }
    if (outputPosition < 0 || outputLength < 0) {
        throwIllegalArgument(env, "output position/length must be non-negative");
        return 0;
    }

    std::string typeName = requireMessageType(env, messageType);
    if (env->ExceptionCheck()) {
        return 0;
    }

    if (!DescriptorRegistry::instance().hasType(typeName)) {
        throwIllegalArgument(env, "Unknown protobuf message type: " + typeName);
        return 0;
    }

    StructuredPinnedBuffers buffers;
    const auto pinStart = std::chrono::steady_clock::now();
    if (!pinStructuredBuffers(env, structuredInputs, buffers)) {
        releaseStructuredPinnedBuffers(env, buffers);
        return 0;
    }
    const auto pinEnd = std::chrono::steady_clock::now();

    try {
        const auto buildStart = std::chrono::steady_clock::now();
        auto inputs = buildStructuredInputs(buffers);
        const auto buildEnd = std::chrono::steady_clock::now();

        std::string result;
        const auto compressStart = std::chrono::steady_clock::now();
        if (compressorBytes != nullptr) {
            std::string serialized = copyArray(env, compressorBytes);
            if (env->ExceptionCheck()) {
                releaseStructuredPinnedBuffers(env, buffers);
                return 0;
            }
            auto& entry = explicitStructuredEntryForCompressor(typeName, serialized);
            result = entry.cctx.compress(inputs);
        } else {
            auto& structuredEntry = structuredSerializerEntryForType(typeName);
            result = structuredEntry.cctx.compress(inputs);
        }
        const auto compressEnd = std::chrono::steady_clock::now();

        releaseStructuredPinnedBuffers(env, buffers);

        const auto outStart = std::chrono::steady_clock::now();
        jint written = writeDirectBuffer(env, outputBuffer, outputPosition, outputLength, result);
        const auto outEnd = std::chrono::steady_clock::now();
        if (!env->ExceptionCheck() && written >= 0) {
            recordStructuredPerf(
                    static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(pinEnd - pinStart).count()),
                    static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(buildEnd - buildStart).count()),
                    static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(compressEnd - compressStart).count()),
                    static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(outEnd - outStart).count()));
        }
        return written;
    } catch (const std::exception& ex) {
        releaseStructuredPinnedBuffers(env, buffers);
        throwIllegalState(env, ex.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLStructuredProtoBridge_decompressStructuredNative(
        JNIEnv* env,
        jclass,
        jbyteArray payload,
        jstring messageType)
{
    if (payload == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "payload");
        return nullptr;
    }

    std::string typeName = requireMessageType(env, messageType);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    if (!DescriptorRegistry::instance().hasType(typeName)) {
        throwIllegalArgument(env, "Unknown protobuf message type: " + typeName);
        return nullptr;
    }

    return decompressStructuredPayload(env, payload, typeName);
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLStructuredProtoBridge_trainStructuredNative(
        JNIEnv* env,
        jclass,
        jobjectArray samples,
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

    std::string typeName = requireMessageType(env, messageType);
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    if (!DescriptorRegistry::instance().hasType(typeName)) {
        throwIllegalArgument(env, "Unknown protobuf message type: " + typeName);
        return nullptr;
    }

    try {
        openzl::Compressor baseCompressor = createStructuredCompressorForType(typeName);
        std::vector<StructuredSampleStorage> storages;
        storages.reserve(static_cast<size_t>(count));
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
            storages.emplace_back(parseStructuredSample(payload));
            auto inputs = buildStructuredInputs(storages.back());
            openzl::training::MultiInput multi;
            for (auto& input : inputs) {
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
        params.compressorGenFunc = [typeName](openzl::poly::string_view serialized) {
            auto up = std::make_unique<openzl::Compressor>(createStructuredCompressorForType(typeName));
            up->deserialize(serialized);
            return up;
        };

        auto trained = openzl::training::train(multiInputs, baseCompressor, params);

        jclass byteArrClass = env->FindClass("[B");
        if (!byteArrClass) {
            return nullptr;
        }
        jobjectArray out = env->NewObjectArray(static_cast<jsize>(trained.size()), byteArrClass, nullptr);
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
                env->SetByteArrayRegion(elem, 0, static_cast<jsize>(sv.size()), reinterpret_cast<const jbyte*>(sv.data()));
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

extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLStructuredProtoBridge_compressStructuredSampleNative(
        JNIEnv* env,
        jclass,
        jbyteArray sample,
        jbyteArray compressorBytes,
        jstring messageType)
{
    if (sample == nullptr) {
        throwNew(env, JniRefs().nullPointerException, "sample");
        return nullptr;
    }

    std::string typeName = requireMessageType(env, messageType);
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    if (!DescriptorRegistry::instance().hasType(typeName)) {
        throwIllegalArgument(env, "Unknown protobuf message type: " + typeName);
        return nullptr;
    }

    try {
        std::string payload = copyArray(env, sample);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        StructuredSampleStorage storage = parseStructuredSample(payload);
        auto inputs = buildStructuredInputs(storage);
        std::string result;
        if (compressorBytes != nullptr) {
            std::string serialized = copyArray(env, compressorBytes);
            if (env->ExceptionCheck()) {
                return nullptr;
            }
            auto& entry = explicitStructuredEntryForCompressor(typeName, serialized);
            result = entry.cctx.compress(inputs);
        } else {
            auto& structuredEntry = structuredSerializerEntryForType(typeName);
            result = structuredEntry.cctx.compress(inputs);
        }
        return makeByteArray(env, result);
    } catch (const std::exception& ex) {
        throwIllegalState(env, ex.what());
        return nullptr;
    }
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
