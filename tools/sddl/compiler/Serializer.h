// Copyright (c) Meta Platforms, Inc. and affiliates.

#pragma once

#include <string>

#include "tools/sddl/compiler/AST.h"
#include "tools/sddl/compiler/Logger.h"
#include "tools/sddl/compiler/Source.h"

namespace openzl::sddl {

/**
 * Serializes an AST to the CBOR format the SDDL graph accepts.
 */
class Serializer {
   public:
    explicit Serializer(const detail::Logger& logger);

    std::string serialize(const ASTVec& ast, const Source& source) const;

   private:
    const detail::Logger& log_;
};

} // namespace openzl::sddl
