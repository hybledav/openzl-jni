# OpenZL JNI 0.3.0-2 interfaces

These additions use upstream OpenZL 0.3.0 and are assigned wrapper version
`0.3.0-2`. Publication is pending release validation; the preceding published
release is `0.3.0-1`.

## Optional codec-output caching

```java
try (OpenZLCompressor compressor = new OpenZLCompressor()) {
    compressor.configureBruteForce(OpenZLGraph.ZSTD, OpenZLGraph.HUFFMAN,
            OpenZLGraph.FSE, OpenZLGraph.ENTROPY, OpenZLGraph.STORE);
    compressor.setTryGraphCacheBudget(2L * 1024 * 1024);
    byte[] frame = compressor.compress(payload);
    byte[] restored = compressor.decompress(frame);
    compressor.setTryGraphCacheBudget(0); // Disable and release cached entries.
}
```

The default is disabled. This caches eligible deterministic standard-codec
outputs during graph trials, including overlapping work in brute-force choices.
The budget covers entry payloads, not all native memory. Reaching the budget
skips new entries rather than failing compression. Dictionary-backed/custom
codecs, string streams and multi-input invocations are not cached.

`getTryGraphCacheBudget()` returns the configured budget. `reset()` and graph
changes retain the setting; changing the budget drops existing entries.
`close()` releases the cache before a native context enters the reuse pool.
Compressor instances remain confined to one concurrent operation at a time.

## Lazy dictionary-bundle providers

`DictionaryBundleProvider` is a functional interface:

```java
byte[] loadBundle(byte[] bundleId);
```

It receives a defensive copy of the frame's canonical 32-byte bundle ID and
returns the complete serialized fat bundle. Use `getDictionaryBundleId(frame)`
to associate an encoded frame with the bundle saved alongside its trained
candidate. For example, given a `frame` and its `candidate`:

```java
Map<String, byte[]> bundles = Map.of(
        Base64.getEncoder().encodeToString(OpenZLCompressor.getDictionaryBundleId(frame)),
        candidate.dictionaryBundle());
try (OpenZLCompressor reader = new OpenZLCompressor()) {
    reader.setDictionaryBundleProvider(id ->
            bundles.get(Base64.getEncoder().encodeToString(id)));
    byte[] restored = reader.decompress(frame);
}
```

`getDictionaryBundleId(ByteBuffer)` reads a direct buffer's remaining frame
without changing its position or limit. Frames without a bundle return an
empty ID and do not trigger the provider. Malformed frames are rejected.

Provider lookups run synchronously in Java, before native array pinning. A null
or empty result fails with `IllegalStateException`; retrieval exceptions are
propagated. Invalid bundles and bundles containing the wrong ID are rejected
before replacing the reader's working dictionary. Recursive lookups and closing
the reader during a lookup fail without accessing freed native state.

Only the most recently resolved ID is cached in each compressor. `reset()`
retains it. Installing a provider or manually replacing a bundle invalidates
that ID cache. Passing null to `setDictionaryBundleProvider` stops callbacks
while retaining the loaded bundle. `close()` releases both. A provider shared
across compressor instances must support concurrent calls; its returned array
must remain stable for the duration of the decoding call.

## Protobuf dictionary candidates

Given your registered schema descriptor and protobuf-wire sample arrays:

```java
TrainOptions options = new TrainOptions();
options.dictionaryTraining = true;
options.maxNumCandidates = 1;
TrainedCompressor[] candidates = OpenZLProtobuf.trainCandidates(
        samples, OpenZLProtobuf.Protocol.PROTO, options, descriptor);
if (candidates.length == 0) throw new IllegalStateException("No candidates");
TrainedCompressor candidate = candidates[0];

byte[] frame = OpenZLProtobuf.convertWithCandidate(samples[0],
        OpenZLProtobuf.Protocol.PROTO, OpenZLProtobuf.Protocol.ZL,
        candidate, descriptor);

// A separate decoder needs the bundle, not the training graph.
byte[] restored = OpenZLProtobuf.convertWithDictionary(frame,
        OpenZLProtobuf.Protocol.PROTO, candidate.dictionaryBundle(), descriptor);
```

Save the candidate's profile name, graph bytes and bundle bytes together.
Protobuf candidates use the profile marker `protobuf`; reload them using
`convertWithCandidate`, rather than the generic compressor's byte-stream
`configureTrained` method.

Dictionary mode groups typed streams and trains a Zstandard dictionary backend.
Ordinary self-contained training keeps the upstream ACE backend. Compare both
on representative data. A request for format 24 with dictionary training is
rejected; dictionary-backed frames require format 25 or newer. The default
remains format 27.

`convertWithDictionaryProvider(frame, outputProtocol, provider, descriptor)`
resolves the bundle from the frame ID before conversion. String message-name
overloads are also available; register those schemas first. Output may be
protobuf, JSON or OpenZL.

The direct-buffer `convertWithCandidate` convenience overload copies the
requested remaining input bytes and preserves position/limit. It accepts
read-only direct sources. The existing conversion methods retain their current
buffer behavior.

Conversion retains one dictionary-configured graph/bundle pair per calling
thread. Switching pairs replaces that context transactionally. Call
`OpenZLProtobuf.clearDictionaryCache()` on the same worker thread to release it
early; thread termination also releases it. Dictionary state is separate from
the existing self-contained conversion cache.

## Existing APIs and native adaptation

Existing `train` methods still return graph-only `byte[][]` and reject
dictionary training. The new conversion names avoid making existing
`convert(..., null, ...)` calls ambiguous. Byte-array decompression retains
its existing result after close. No new wire format or external dependency is
introduced.

Upstream's Protobuf decoder keeps its decompression context private. The build's
existing Protobuf context patch adds a dictionary-loader attachment method,
marked in the modified header. The same patch retains its target-format fix.
The source provenance and 24-entry license inventory remain unchanged.

Custom codec materializers and zero-copy multi-input support remain outside this revision.

## Mixed typed inputs and outputs

```java
try (OpenZLCompressor compressor = new OpenZLCompressor(OpenZLGraph.GENERIC)) {
    byte[] frame = compressor.compressInputs(
            TypedInput.serial(new byte[] {1, 2, 3}),
            TypedInput.ints(new int[] {10, 20, 30}),
            TypedInput.structs(new byte[] {1, 2, 3, 4, 5, 6}, 3),
            TypedInput.strings(new byte[] {0, -1, 42}, new int[] {0, 2, 1}));
    TypedOutput[] outputs = compressor.decompressOutputs(frame);
    int[] numbers = outputs[1].ints();
    int[] stringLengths = outputs[3].stringLengths();
}
```

One frame regenerates outputs in input order, with their logical type, element
width and element count. `GENERIC` supports mixed/multiple inputs; other graphs
retain their native input restrictions. Incompatible graph/input combinations
fail with `OpenZLException`. The typed reader also accepts ordinary single-input
frames and resolves dictionary providers before decoding.

`TypedInput` factories snapshot arrays or the remaining bytes of a heap/direct/
read-only `ByteBuffer`; these overloads copy data. They preserve buffer position
and limit, including unaligned slices. Numeric bytes use native byte order;
`numeric(ByteBuffer, width)` requires the buffer to declare that order. The
`ints`, `longs`, `floats` and `doubles` factories do this automatically. Numeric
streams encode width and bits, without a signed/float tag: choose the matching
output accessor. NaN payloads and signed zero are preserved.

Fixed-width structs accept any positive width dividing the byte count. Numeric
widths are 1, 2, 4 or 8. String streams contain arbitrary binary bytes plus
nonnegative lengths whose sum must equal the byte count. Empty streams and
empty strings are supported. At least one input is required. Outputs own their
bytes after the compressor closes; getters return defensive copies.
`elementWidth()` returns 0 for variable-length strings, and `stringLengths()`
rejects other types. Each output must fit in a Java array; frame metadata is
checked before native output allocation.

## Typed context options

```java
compressor.setCompressionOptions(CompressionOptions.builder()
        .compressionLevel(3).decompressionLevel(2).formatVersion(27)
        .compressedChecksum(ParameterSwitch.ENABLED)
        .contentChecksum(ParameterSwitch.ENABLED)
        .permissive(ParameterSwitch.DISABLED).build());
compressor.setDecompressionOptions(DecompressionOptions.builder()
        .checkCompressedChecksum(ParameterSwitch.ENABLED)
        .checkContentChecksum(ParameterSwitch.ENABLED)
        .codecFusion(ParameterSwitch.DISABLED).build());
```

Options are immutable complete snapshots; unspecified fields restore the native
context default or inherited compressor setting. Switches are `DEFAULT`,
`ENABLED` or `DISABLED`. Compression options also expose `minStreamSize` and
`storeOnExpansion`. Both checksum verifications and fusion are enabled by the
current native default. Disabling checksum verification permits corrupted
content to pass undetected, as the tests explicitly demonstrate.

Context options survive repeated operations. `reset()` clears them; `close()`
prevents them from leaking into a pooled context's next owner. Context overrides
take priority over the legacy `setParameter` settings stored on the compressor;
legacy `getParameter` continues to report those compressor settings. Rejected
native updates roll back the complete previous context snapshot. Builders check
supported ranges before entering native code.

## Advanced LZ controls

```java
compressor.configureLz(LzOptions.builder()
        .strategy(LzOptions.Strategy.LAZY).compressionLevel(3)
        .windowLog(16).acceleration(1)
        .hashLog1(14).hashLog2(14).hashLength(4).searchLog(2)
        .entropyMinGainBytes(50).entropyMinGainPercent(1).build());
```

This selects a parameterized standard LZ graph, keeping context options in
effect. Strategies include `DEFAULT`, `FAST`, `DOUBLE_FAST`, `GREEDY`, `LAZY`
and `LAZY2`. Unspecified settings follow upstream defaults. Entropy thresholds
use -1 for default and accept an explicit zero; other controls use zero for
default. Bounds follow the pinned upstream LZ header, with invalid values
rejected rather than silently clamped. Strategy-specific ignored parameters
follow upstream behavior (for example, greedy/lazy strategies ignore hashLog2).
The selected graph and its local settings survive `reset()` and binary graph
serialization. `graph()` still identifies the constructor's initial graph,
consistent with existing profile configuration methods.

## Native errors and warnings

Typed compression/decompression throw `OpenZLException`, an
`IllegalStateException` subclass with `errorCode()`, `operation()` and
`nativeContext()`. The code is the upstream `ZL_ErrorCode`, or -1 when the
failure has no upstream code. Argument validation uses the usual Java
exceptions. Existing byte-array APIs retain their established null/error behavior.

After a successful operation, `getCompressionWarnings()` and
`getDecompressionWarnings()` snapshot the corresponding context's warnings.
For example, enabling permissive compression can turn a graph/type mismatch
into a successful fallback plus a warning. Each warning has `errorCode()`,
`message()` and `nativeContext()`. Snapshots remain valid across later operations
and close. A new or reset instance returns no previous owner's/session's
warnings. Inspect warnings before another operation invalidates native storage.

## Validation

Java 25 build and runtime support is also verified locally, including a newer
coverage agent and explicit native-access settings. See the
[Java 25 validation record](JAVA_25.md). Java 11 remains the minimum runtime.

The full Maven reactor and 236 tests pass locally on Linux x86_64 with Java 21
and `-Xcheck:jni`. The 236-test unit suite also passes on Java 11 and against
the ASan/UBSan build on Java 21. A runtime library hash check confirms the
instrumented unit-test JVM loaded the instrumented native binary. Release and
instrumented stress runs each pass 40,000 iterations; no sanitizer or JNI
findings were observed. Regression coverage includes cache budgets and pooling,
offset/direct decoding, provider failures and reentrancy, shared providers,
Protobuf dictionary persistence, missing/corrupt/wrong bundles, independent
worker contexts, and self-contained format-24 candidates.

The four latest interfaces and their `OpenZLCompressor` entry points have local
JaCoCo 0.8.11 coverage of **98/98 branches (100%)**, with all their executable
lines covered. This is Java coverage, not C/C++ branch coverage or whole-project
100% coverage. No coverage gate or CI workflow changes were added. Tests include
400 deterministic randomized mixed frames across four workers, 300 corrupted
mixed frames, every truncated prefix of a mixed frame, lossless LZ strategies,
a functional window-size compression check, checksum corruption detection and
bypass, fusion on/off, real permissive-fallback warnings, pooling isolation,
and dictionary-provider integration.

Native-source license checks and base/source/Javadoc/Linux JAR checks pass.
macOS and Windows binaries must be rebuilt and tested by CI before release;
old `0.3.0-1` platform libraries must not be relabeled as a new wrapper build.

Sanitizer limits match the previous port validation: UBSan is disabled on
`flags_reflection`, `libprotobuf` and `libprotoc` for the GCC/Abseil compile issue,
with ASan retained. External XGBoost/dmlc and assembly are outside full
instrumentation. LeakSanitizer is disabled for HotSpot; no leak/race guarantee
is implied. These record local validation before the 0.3.0-2 cross-platform CI run;
they do not by themselves establish a published release.
