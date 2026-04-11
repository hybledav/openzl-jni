# Third-Party Components

## OpenZL

- Upstream project: `facebook/openzl`
- Upstream repository: `https://github.com/facebook/openzl.git`
- Version used by this repository: `v0.1.0`
- License: BSD-3-Clause
- Primary integration point: `CMakeLists.txt`

`openzl-jni` builds and redistributes JNI bindings together with native code that
is compiled from the upstream OpenZL sources.

### Local Build-Time Adjustments

This repository applies small compatibility fixes to fetched upstream sources
during CI and local native builds:

- `patches/fix_future_capture.py`
- `cmake/shims/logger_string_compat.h`
- `cmake/shims/tools_io_stdexcept_compat.h`

These adjustments exist to keep the upstream OpenZL sources building cleanly on
the supported toolchains used by this repository. They do not change the
declared upstream license.

### Redistribution Notes

When distributing artifacts that embed OpenZL-derived native binaries, include:

- `LICENSE`
- `NOTICE`
- this provenance file or equivalent third-party attribution material
