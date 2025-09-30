// Copyright (c) Meta Platforms, Inc. and affiliates.

#pragma once

#include <iostream>
#include <string>

#include "openzl/cpp/poly/StringView.hpp"

#include "tools/sddl/compiler/Grouper.h"
#include "tools/sddl/compiler/Logger.h"
#include "tools/sddl/compiler/Parser.h"
#include "tools/sddl/compiler/Serializer.h"
#include "tools/sddl/compiler/Tokenizer.h"

namespace openzl::sddl {

class Compiler {
   public:
    /**
     * Creates a compiler instance, with an optional @p log output stream in
     * which to collect messages, the verbosity of which is controlled by @p
     * verbosity.
     *
     * The semantics of the verbosity levels are loosely defined, but
     * approximately, < 0 means no output, 0 means only errors, > 0 produces
     * increasingly verbose context and debug output. The max verbosity is
     * 4 or 5 or so.
     */
    explicit Compiler(std::ostream& log = std::cerr, int verbosity = 0);

    /**
     * This function translates a program @p source in the Data Description
     * Driven Dispatch language to the binary compiled representation that the
     * SDDL graph accepts in OpenZL.
     *
     * @param source a human-readable description in the SDDL Language.
     * @param filename an optional string identifying the source of the @p
     *                 source code, which will be included in the pretty
     *                 error message if compilation fails. If the input
     *                 didn't come from a source readily identifiable with a
     *                 string that would be meaningful to the user / consumer
     *                 of error messages, you can just use `[input]` or some-
     *                 thing, I dunno.
     * @returns the compiled binary representation of the description, which
     *          the SDDL graph accepts. See the SDDL graph documentation for
     *          a description of the format of this representation.
     * @throws CompilerException if compilation fails. Additional context can
     *         be found in the output log provided to the compiler during
     *         construction, if a suitably high verbosity has been selected.
     */
    std::string compile(poly::string_view source, poly::string_view filename)
            const;

   private:
    const detail::Logger logger_;

    const Tokenizer tokenizer_;
    const Grouper grouper_;
    const Parser parser_;
    const Serializer serializer_;
};

} // namespace openzl::sddl
