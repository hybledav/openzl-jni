#!/usr/bin/env python3
from pathlib import Path
import sys

POSSIBLE_TARGETS = [
    Path('_deps/openzl-src/tools/training/clustering/compression_utils.cpp'),
    Path('cmake_build/_deps/openzl-src/tools/training/clustering/compression_utils.cpp'),
    Path('_deps/openzl-src/tools/training/clustering/trainers/bottom_up_trainer.cpp'),
    Path('cmake_build/_deps/openzl-src/tools/training/clustering/trainers/bottom_up_trainer.cpp'),
]

# Trainer directories to scan for any .cpp that uses high_resolution_clock
TRAINER_DIR_CANDIDATES = [
    Path('_deps/openzl-src/tools/training/clustering/trainers'),
    Path('cmake_build/_deps/openzl-src/tools/training/clustering/trainers'),
]

# Logger header candidates to ensure <string> is present for std::to_string
LOGGER_CANDIDATES = [
    Path('_deps/openzl-src/tools/logger/Logger.h'),
    Path('cmake_build/_deps/openzl-src/tools/logger/Logger.h'),
]

# Utils directories to scan for std::iota usage
UTILS_DIR_CANDIDATES = [
    Path('_deps/openzl-src/tools/training/utils'),
    Path('cmake_build/_deps/openzl-src/tools/training/utils'),
]

old_marker = 'return threadPool_->run([futures = std::move(futures)]() mutable {'
patched_any = False

for TARGET in POSSIBLE_TARGETS:
    if not TARGET.exists():
        # Try next candidate
        continue
    try:
        text = TARGET.read_text(encoding='utf-8')
    except Exception as e:
        print(f'Unable to read {TARGET}: {e}')
        continue

    # If this is compression_utils.cpp, try to replace the moved futures lambda
    if TARGET.name == 'compression_utils.cpp':
        idx = text.find(old_marker)
        if idx == -1:
            print(f'Old marker not found in {TARGET}; skipping compression_utils patch')
        else:
            # find the end of the lambda block (the matching '});' after the marker)
            end_idx = text.find('\n    });', idx)
            if end_idx == -1:
                print(f'Failed to locate end of lambda block in {TARGET}. Aborting for this file.')
            else:
                end_idx += len('\n    });')
                new_block = (
                    "auto futuresPtr = std::make_shared<std::vector<std::future<SizeTimePair>>>(std::move(futures));\n"
                    "    return threadPool_->run([futuresPtr]() mutable {\n"
                    "        SizeTimePair result{};\n"
                    "        for (auto& future : *futuresPtr) {\n"
                    "            result = result + future.get();\n"
                    "        }\n"
                    "        return result;\n"
                    "    });"
                )
                new_text = text[:idx] + new_block + text[end_idx:]
                try:
                    TARGET.write_text(new_text, encoding='utf-8')
                    print(f'Applied compression_utils patch to {TARGET}')
                    patched_any = True
                    # Update text variable so subsequent checks see latest file content
                    text = new_text
                except Exception as e:
                    print(f'Failed to write patched file {TARGET}: {e}')

    # If this is bottom_up_trainer.cpp, ensure <chrono> is included
    if TARGET.name == 'bottom_up_trainer.cpp':
        try:
            if '#include <chrono>' not in text:
                # Find the position after the last include directive and insert chrono
                # Search for the last line that starts with #include
                lines = text.splitlines(keepends=True)
                insert_idx = 0
                for i, line in enumerate(lines):
                    if line.lstrip().startswith('#include'):
                        insert_idx = i + 1
                # Insert the include at insert_idx
                lines.insert(insert_idx, '#include <chrono>\n')
                new_t = ''.join(lines)
                TARGET.write_text(new_t, encoding='utf-8')
                print(f'Inserted <chrono> include into {TARGET}')
                patched_any = True
                # Update text so we don't re-insert later
                text = new_t
        except Exception as e:
            print(f'Failed to insert chrono include into {TARGET}: {e}')

    # Also, for any trainers directory files, ensure chrono is included if
    # they reference high_resolution_clock (covers greedy_trainer, full_split_trainer, etc.)
    try:
        if 'trainers' in str(TARGET.parent):
            if 'high_resolution_clock' in text and '#include <chrono>' not in text:
                lines = text.splitlines(keepends=True)
                insert_idx = 0
                for i, line in enumerate(lines):
                    if line.lstrip().startswith('#include'):
                        insert_idx = i + 1
                lines.insert(insert_idx, '#include <chrono>\n')
                new_t = ''.join(lines)
                TARGET.write_text(new_t, encoding='utf-8')
                print(f'Inserted <chrono> include into trainer file {TARGET}')
                patched_any = True
                text = new_t
    except Exception as e:
        print(f'Failed to insert chrono into trainers file {TARGET}: {e}')

# Now scan trainer directories for any other .cpp files that need chrono
for DIR in TRAINER_DIR_CANDIDATES:
    if not DIR.exists() or not DIR.is_dir():
        continue
    for cpp in DIR.glob('*.cpp'):
        try:
            t = cpp.read_text(encoding='utf-8')
        except Exception as e:
            print(f'Unable to read {cpp}: {e}')
            continue
        if 'high_resolution_clock' in t and '#include <chrono>' not in t:
            try:
                lines = t.splitlines(keepends=True)
                insert_idx = 0
                for i, line in enumerate(lines):
                    if line.lstrip().startswith('#include'):
                        insert_idx = i + 1
                lines.insert(insert_idx, '#include <chrono>\n')
                new_t = ''.join(lines)
                cpp.write_text(new_t, encoding='utf-8')
                print(f'Inserted <chrono> include into {cpp}')
                patched_any = True
            except Exception as e:
                print(f'Failed to insert chrono into {cpp}: {e}')

# Ensure logger header includes <string> if it references std::to_string
for LPATH in LOGGER_CANDIDATES:
    if not LPATH.exists():
        continue
    try:
        lh = LPATH.read_text(encoding='utf-8')
    except Exception as e:
        print(f'Unable to read {LPATH}: {e}')
        continue
    if 'to_string(' in lh and '#include <string>' not in lh:
        try:
            lines = lh.splitlines(keepends=True)
            insert_idx = 0
            for i, line in enumerate(lines):
                if line.lstrip().startswith('#include'):
                    insert_idx = i + 1
            lines.insert(insert_idx, '#include <string>\n')
            new_l = ''.join(lines)
            LPATH.write_text(new_l, encoding='utf-8')
            print(f'Inserted <string> include into {LPATH}')
            patched_any = True
        except Exception as e:
            print(f'Failed to insert <string> into {LPATH}: {e}')

# Ensure <numeric> is included for files that use std::iota
for UDIR in UTILS_DIR_CANDIDATES:
    if not UDIR.exists() or not UDIR.is_dir():
        continue
    for cpp in UDIR.glob('*.cpp'):
        try:
            t = cpp.read_text(encoding='utf-8')
        except Exception as e:
            print(f'Unable to read {cpp}: {e}')
            continue
        if ('iota(' in t or 'std::iota' in t) and '#include <numeric>' not in t:
            try:
                lines = t.splitlines(keepends=True)
                insert_idx = 0
                for i, line in enumerate(lines):
                    if line.lstrip().startswith('#include'):
                        insert_idx = i + 1
                lines.insert(insert_idx, '#include <numeric>\n')
                new_t = ''.join(lines)
                cpp.write_text(new_t, encoding='utf-8')
                print(f'Inserted <numeric> include into {cpp}')
                patched_any = True
            except Exception as e:
                print(f'Failed to insert <numeric> into {cpp}: {e}')

if not patched_any:
    print('No files patched (none of the expected paths existed or no changes necessary).')
    # Exit 0 so CI doesn't fail; this script is a best-effort patch
    sys.exit(0)
