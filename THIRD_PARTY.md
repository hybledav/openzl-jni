# Third-party native components

The release uses OpenZL 0.3.0. Platform JARs include the native library,
its full third-party license texts, copyright notices and an inventory
under `META-INF/licenses`, `META-INF/THIRD-PARTY` and
`META-INF/native-licenses.json`.

The inventory records the exact source locations and SHA-256 values for
OpenZL, Protobuf, Abseil, utf8_range, Zstandard, LZ4, XGBoost, dmlc-core,
xxHash, pdqsort, Ryu, Microsoft GSL, ConcurrentQueue, sweater and the bundled FSE/HUF implementation.

Windows JARs also carry the GCC, MinGW-w64 and winpthreads supplier
documents copied from the actual compiler distribution. They include
the GCC Runtime Library Exception. These documents and checksums are
under `lib/windows_amd64/licenses` and
`lib/windows_amd64/runtime-licenses.json`.

The source and Javadoc JARs include the wrapper license, attribution
and native dependency notices. Native libraries are packaged only
in their platform JARs.

## Build and release checks

CI compares the checked-in license texts against the native dependency
sources after building. It then checks the finished base, source, Javadoc
and platform JARs, including native binary headers, before uploading artifacts.
The publishing job repeats
the archive checks after collecting all three platform libraries and
before uploading to Sonatype.

The Linux build also exchanges format-24 byte-array, direct-buffer and numeric
frames with the published JNI 0.2.1 binary (OpenZL 0.2.0) in isolated JVMs.
It checks that the old reader rejects the new default format 27.

For a local build, run `python3 scripts/release_licenses.py sources` after
building the native library, then run
`python3 scripts/release_licenses.py verify --version 0.3.0-1 --classifier linux_amd64`
after Maven packaging. Windows builds additionally use the
`windows-runtime` command with `cmake_build/native-compiler.txt` and their
native resource directory.

When upgrading a native dependency, refresh its inventory entry and
license documents from the selected upstream sources. License text
changes cause CI to fail until the notices are reviewed.

## Modifications to upstream sources

The build applies compatibility adjustments from
`patches/fix_future_capture.py`,
`cmake/patch_openzl_windows_xgboost.cmake`,
`cmake/patch_openzl_protobuf_context.cmake`,
`cmake/shims/logger_string_compat.h` and
`cmake/shims/tools_io_stdexcept_compat.h`.
The bundled pdqsort source is marked by upstream as modified for OpenZL.
