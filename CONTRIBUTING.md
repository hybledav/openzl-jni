# Contributing to openzl-jni

Thanks for taking the time to improve **openzl-jni**. The project aims to provide
usable Java bindings for Meta’s OpenZL compressor, and contributions of all
sizes help move it forward. This guide explains how to propose changes and what
we look for during review.

## Getting Started

- Fork the repository and create a feature branch from `dev`.
- Keep each branch focused on a single change set; open separate pull requests
  for unrelated fixes.
- Sync with upstream `dev` before you start to avoid unnecessary merge work.

## Development Workflow

Native and Java code live side by side; validating both parts is encouraged.

```bash
# build the native library
cmake -S . -B cmake_build -DCMAKE_BUILD_TYPE=Release
cmake --build cmake_build --target openzl_jni

# refresh the bundled shared object and run the Maven build
mkdir -p JNI/openzl-jni/src/main/resources/lib/linux_amd64
cp cmake_build/cli/libopenzl_jni.so JNI/openzl-jni/src/main/resources/lib/linux_amd64/
mvn -pl JNI/openzl-jni -am clean package
```

- Prefer incremental rebuilds (`cmake --build cmake_build --target openzl_jni`)
  while iterating; copy the `.so` into the resources directory when you need to
  exercise Java tests.
- Java unit and integration tests run with `mvn -pl JNI/openzl-jni -am test`.
- Add or update tests whenever behaviour changes; large features without test
  coverage are unlikely to be merged.

## Coding Guidelines

- Match the existing code style in each language; avoid sweeping reformatting
  so reviewers can focus on functional changes.
- Keep dependencies minimal and justify new ones in the pull request description.
- Update user-facing documentation (`README.md`, examples, JavaDoc) when APIs or
  build steps change.
- Include concise commit messages that describe the intent of each change.

## Submitting a Pull Request

- Rebase on the latest `dev` and ensure `mvn -pl JNI/openzl-jni -am verify`
  passes before opening the pull request.
- Fill out the pull request template, reference any related issues, and call out
  anything that needs special attention (e.g. platform-specific behaviour).
- Expect to engage in review. Address feedback through follow-up commits or
  comment threads; avoid force-pushing unless you are asked to tidy history.

## Reporting Issues

- Use GitHub Issues for bug reports and feature requests. Include the observed
  behaviour, reproduction steps, environment details (OS, JVM, compiler), and
  expected outcome.
- For confidentiality or security-sensitive disclosures, please avoid filing a
  public issue and reach out privately to the maintainers instead.

## License

By submitting a contribution, you agree that it will be licensed under the
BSD-3-Clause license found in `LICENSE`.*** End Patch
