# Managed compression validation (0.3.0-2)

The managed path is `compress(ByteBuffer, OpenZLBufferManager)` and its matching
decompression overload. Testing on Java 25 found pool correctness issues that
also reproduce on Java 21. They are not a Java 25 JNI regression.

## Findings and fixes

- A failed compression or decompression did not return its borrowed destination
  to the pool. The helpers now return that buffer before propagating the
  exception, preserving the source cursor on failure.
- `release()` adopted foreign direct buffers despite its documented contract.
  That could include a read-only buffer, a slice/duplicate, another manager's
  buffer, or a buffer borrowed on a different thread. The pool now accepts only
  the exact object currently borrowed from the calling thread's pool.
- Releasing a pooled buffer twice inserted it twice, allowing two active callers
  to receive the same storage. An already returned buffer is now ignored.
- Clearing the calling thread's pool now also clears its ownership records, so
  late returns from an earlier pool cannot be adopted by a new pool.

Ownership uses identity comparisons rather than `ByteBuffer.equals()` or its
content-dependent hash code. Checked-out buffers are tracked weakly, allowing
abandoned buffers to remain eligible for JVM cleanup. Callers must stop using
buffers once returned; a raw `ByteBuffer` alias is not a lease token.

The existing thread-local lifecycle is preserved: `close()` clears only the
calling thread's state. Workers using a shared manager should each close their
own state. Closing does not invalidate buffers still held by callers or force
immediate native-memory reclamation.

## Local results, 2026-11-14

Linux x86_64, OpenJDK 25.0.1+8, with explicit native access and illegal native
access denied:

- Before the fix: five new regression tests failed on both Java 25 and Java 21.
- After the fix: **245 tests pass** on Java 11, Java 21 and Java 25, and in the
  Java 25 ASan/UBSan suite. All runs use `-Xcheck:jni` and a **128 MiB direct-memory
  limit**. The instrumented JVM's native-library hash and ASan runtime are checked.
- Each suite includes **10,000 managed round trips across four worker contexts**
  and **200 short-lived tasks**. The latter run on virtual threads on Java 21/25
  and on platform threads on Java 11.
- Payloads include empty input, boundary sizes, random and repetitive data,
  and inputs up to 1 MiB. Tests cover ZSTD, GENERIC, configured LZ, codec fusion
  enabled/disabled, read-only sources, nonzero positions and buffer reuse.
- Deterministic failure tests verify destination reuse and source cursors after
  native compression failures, corrupted-frame decoding, a missing dictionary
  provider result and closed-compressor calls. Dictionary-backed managed
  compression/decompression and recovery are exercised with a real trained bundle.
- Foreign buffers, views, duplicate returns, cross-thread returns, and late
  returns after pool clearing are checked by identity and content assertions.
- Separate Java 11 and Java 25 JVMs each allocate and abandon **256 MiB** of
  checkouts while keeping the manager live under a **32 MiB direct-memory limit**.
  Both pass, with measured direct-buffer storage never exceeding 32 MiB. This
  verifies that ownership tracking does not retain abandoned checkouts strongly.
- Base, sources, Javadoc and Linux native artifact license checks pass. Native
  sources and the dependency inventory are unchanged by these fixes.

No JNI checking diagnostics or sanitizer findings were observed. The existing
[sanitizer limits](JAVA_25.md) apply. macOS and Windows remain untested locally.
These fixes are included in the 0.3.0-2 release candidate. No coverage gate was added;
publication follows cross-platform release validation.

The regression tests are in `TestManagedCompression` and
`TestDictionaryBundleProvider`. Local logs, artifact hashes and reproduction
details are retained under `.cache/managed-compression/`.
