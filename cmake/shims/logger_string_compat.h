#pragma once

// Ensures <string> is included before compiling the OpenZL logger so that
// std::to_string is available on toolchains where the upstream sources omit it.
#include <string>
