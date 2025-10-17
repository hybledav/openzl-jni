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
- [ ] Broader parameter management (format version, data arenas, custom parameters)

## Profiles & higher-level graphs
- [x] Pre-built profiles exposed through `OpenZLProfile`
- [x] Profile arguments via `configureProfile(OpenZLProfile, Map<String,String>)`
- [x] Compression-level persistence across resets/profile switches
- [ ] CLI parity for profile discovery/training workflows

## SDDL and structured data
- [x] SDDL compilation (`OpenZLSddl.compile`)
- [x] Configuring compiled SDDL programs (`configureSddl`)
- [x] Compression-level interplay with SDDL (tested)
- [ ] Richer SDDL compiler diagnostics surfaced to Java

## Typed/numeric helpers
- [x] Numeric array helpers (`compressInts`, `compressLongs`, `compressFloats`, `compressDoubles`)

## Buffering & memory management
- [x] Off-heap buffer pooling (`OpenZLBufferManager`)
- [x] Direct-buffer compressor convenience (`compress(src, buffers)` variants)
- [ ] Data arena support (wrapping `ZL_CCtx_setDataArena` etc.)
- [ ] Zero-copy multi-input support

## Build & packaging
- [x] Cross-platform native classifiers via CI
- [x] JNI packaging with classifier jars
- [x] Automated publish pipeline to Maven Central
