# OpenZL JNI Feature Checklist

## Core compression workflow
- [x] Byte-array compression/decompression via `OpenZLCompressor`
- [x] Direct `ByteBuffer` compression/decompression with `OpenZLBufferManager`
- [x] Compression bound helper `OpenZLCompressor.maxCompressedSize`
- [ ] Streaming or chunked typed references - (waiting for upstream [#128](https://github.com/facebook/openzl/issues/128))
- [ ] Multi-input and typed-reference compression (`ZL_CCtx_compressMultiTypedRef`, `ZL_TypedRef_*`)
- [ ] Detailed error/warning access (`ZL_CCtx_getErrorContextString`, `ZL_CCtx_getWarnings`) (optional)

## Configuration & introspection
- [x] Graph selection through `OpenZLGraph`
- [x] Configuration serialization (`serialize`, `serializeToJson`)
- [x] Frame metadata inspection via `OpenZLCompressionInfo`
- [x] Compression-level get/set with `OpenZLCompressionLevel`
- [x] Frame format version get/set, including format 24 for OpenZL 0.2.0 readers
- [ ] Broader parameter management (custom parameters)

## Profiles & higher-level graphs
- [x] Pre-built profiles exposed through `OpenZLProfile`
- [x] Profile arguments via `configureProfile(OpenZLProfile, Map<String,String>)`
- [x] Compression-level persistence across resets/profile switches
- [x] CLI parity for profile discovery/training workflows
- [x] OpenZL 0.3.0 LZ, big-endian numeric and SDDL2 profiles
- [x] Dictionary bundle training and loading for compression/decompression
- [x] Expose the upstream training candidate limit and target format
- [x] Standalone numeric Transformer and serializable brute-force graph configuration
- [x] Dictionary bundle support in the Protobuf-specific conversion/training API
- [x] Optional codec output cache control
- [x] Lazy Java dictionary-bundle provider with frame bundle-ID lookup
- [x] Typed compression/decompression options, including checksums and codec fusion
- [x] Advanced LZ parameters and strategy selection
- [x] Native error codes/context and warning snapshots for typed operations

## SDDL and structured data
- [x] SDDL compilation (`OpenZLSddl.compile`)
- [x] Configuring compiled SDDL programs (`configureSddl`)
- [x] Compression-level interplay with SDDL (tested)
- [ ] Richer SDDL compiler diagnostics surfaced to Java

## Typed/numeric helpers
- [x] Mixed typed multi-input frames and typed outputs (snapshot arrays/buffers)
- [x] Numeric array helpers (`compressInts`, `compressLongs`, `compressFloats`, `compressDoubles`)

## Buffering & memory management
- [x] Off-heap buffer pooling (`OpenZLBufferManager`)
- [x] Direct-buffer compressor convenience (`compress(src, buffers)` variants)
- [x] Data arena support (wrapping `ZL_CCtx_setDataArena` etc.)
- [ ] Zero-copy multi-input support

## Build & packaging
- [x] Cross-platform native classifiers via CI
- [x] JNI packaging with classifier jars
- [x] Automated publish pipeline to Maven Central
