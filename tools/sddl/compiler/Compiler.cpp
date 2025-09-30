// Copyright (c) Meta Platforms, Inc. and affiliates.

#include "tools/sddl/compiler/Compiler.h"

#include "tools/sddl/compiler/Grouper.h"
#include "tools/sddl/compiler/Parser.h"
#include "tools/sddl/compiler/Serializer.h"
#include "tools/sddl/compiler/Source.h"
#include "tools/sddl/compiler/Tokenizer.h"

namespace openzl::sddl {

Compiler::Compiler(std::ostream& os, int verbosity)
        : logger_(os, verbosity),
          tokenizer_(logger_),
          grouper_(logger_),
          parser_(logger_),
          serializer_(logger_)
{
}

/**
 * The compiler for SDDL is comprised of four passes:
 *
 * 1. Tokenization:
 *
 *    Converts the contiguous string of source code into a flat list of tokens.
 *    Strips whitespace and comments.
 *
 *    E.g., `arr = Array(foo, bar + 1); consume arr;` ->
 *    ```
 *    [
 *      Word("arr"), Symbol::ASSIGN, Symbol::ARRAY, Symbol::PAREN_OPEN,
 *      Word("foo"), Symbol::COMMA, Word("bar"), Symbol::ADD, Num(1),
 *      Symbol::PAREN_CLOSE, Symbol::SEMI, Symbol::CONSUME, Word("arr"),
 *      Symbol::SEMI,
 *    ]
 *    ```
 *
 * 2. Grouping:
 *
 *    Breaks the flat list of tokens into explicitly separated groups of tokens.
 *    Removes all separator tokens from the token stream.
 *
 *    a) Splits the top level stream into statements based on the statement
 *       separator.
 *    b) Groups list expressions (parentheses, etc.) into a list node with an
 *       expression for each element.
 *
 *    E.g., the token list from above would become approximately:
 *
 *    ```
 *    [
 *      Expr([
 *        Word("arr"), Symbol::ASSIGN, Symbol::ARRAY,
 *        List(PAREN, [
 *          Expr([Word("foo")]),
 *          Expr([Word("bar"), Symbol::ADD, Num(1)]),
 *        ]),
 *      ]),
 *      Expr([Symbol::CONSUME, Word("arr")]),
 *    ]
 *    ```
 *
 * 3. Parsing:
 *
 *    For each statement, transforms the flat list of tokens into an expression
 *    tree.
 *
 *    E.g.,
 *    ```
 *    [
 *      Op(
 *        ASSIGN,
 *        Var("arr"),
 *        Array(
 *          Var("foo"),
 *          Op(
 *            ADD,
 *            Var("bar"),
 *            Num(1),
 *          ),
 *        ),
 *      ),
 *      Op(
 *        CONSUME,
 *        Var("arr"),
 *      ),
 *    ]
 *    ```
 *
 * 4. Serialization:
 *
 *    Converts the expression trees into the corresponding CBOR tree and
 *    serializes that tree to its binary representation.
 */

std::string Compiler::compile(
        poly::string_view source,
        poly::string_view filename) const
{
    const Source src{ source, filename };
    const auto tokens = tokenizer_.tokenize(src);
    const auto groups = grouper_.group(tokens);
    const auto tree   = parser_.parse(groups);
    return serializer_.serialize(tree, src);
}
} // namespace openzl::sddl
