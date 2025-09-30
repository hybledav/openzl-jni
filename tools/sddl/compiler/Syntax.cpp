// Copyright (c) Meta Platforms, Inc. and affiliates.

#include "tools/sddl/compiler/Syntax.h"

#include <stdexcept>

#include "tools/sddl/compiler/Exception.h"

namespace openzl::sddl {

static const std::map<ListType, poly::string_view> list_types_to_debug_strs{
    { ListType::PAREN, "PAREN" },
    { ListType::SQUARE, "SQUARE" },
    { ListType::CURLY, "CURLY" },
};

poly::string_view list_type_to_debug_str(ListType list_type)
{
    try {
        return list_types_to_debug_strs.at(list_type);
    } catch (const std::out_of_range&) {
        throw InvariantViolation("Lookup failed in list_type_to_debug_str()");
    }
}

ListSymSet::ListSymSet(ListType _type, Symbol _open, Symbol _close, Symbol _sep)
        : type(_type), open(_open), close(_close), sep(_sep)
{
}

const std::map<Symbol, ListSymSet> list_sym_sets{ []() {
    const std::vector<ListSymSet> sets{
        { ListType::PAREN,
          Symbol::PAREN_OPEN,
          Symbol::PAREN_CLOSE,
          Symbol::COMMA },
        { ListType::SQUARE,
          Symbol::SQUARE_OPEN,
          Symbol::SQUARE_CLOSE,
          Symbol::COMMA },
        { ListType::CURLY,
          Symbol::CURLY_OPEN,
          Symbol::CURLY_CLOSE,
          Symbol::SEMI },
    };
    std::map<Symbol, ListSymSet> m;
    for (const auto& set : sets) {
        m.emplace(set.open, set);
    }
    return m;
}() };

static const std::map<Symbol, SymbolType> sym_types{
    { Symbol::NL, SymbolType::GROUPING },
    { Symbol::SEMI, SymbolType::GROUPING },
    { Symbol::COMMA, SymbolType::GROUPING },
    { Symbol::PAREN_OPEN, SymbolType::GROUPING },
    { Symbol::PAREN_CLOSE, SymbolType::GROUPING },
    { Symbol::CURLY_OPEN, SymbolType::GROUPING },
    { Symbol::CURLY_CLOSE, SymbolType::GROUPING },
    { Symbol::SQUARE_OPEN, SymbolType::GROUPING },
    { Symbol::SQUARE_CLOSE, SymbolType::GROUPING },

    { Symbol::DIE, SymbolType::OPERATOR },
    { Symbol::EXPECT, SymbolType::OPERATOR },
    { Symbol::CONSUME, SymbolType::OPERATOR },
    { Symbol::SIZEOF, SymbolType::OPERATOR },
    { Symbol::SEND, SymbolType::OPERATOR },
    { Symbol::ASSIGN, SymbolType::OPERATOR },
    { Symbol::ASSUME, SymbolType::OPERATOR },
    { Symbol::MEMBER, SymbolType::OPERATOR },
    { Symbol::BIND, SymbolType::OPERATOR },

    { Symbol::NEG, SymbolType::OPERATOR },

    { Symbol::EQ, SymbolType::OPERATOR },
    { Symbol::NE, SymbolType::OPERATOR },
    { Symbol::ADD, SymbolType::OPERATOR },
    { Symbol::SUB, SymbolType::OPERATOR },
    { Symbol::MUL, SymbolType::OPERATOR },
    { Symbol::DIV, SymbolType::OPERATOR },
    { Symbol::MOD, SymbolType::OPERATOR },

    { Symbol::BYTE, SymbolType::KEYWORD },
    { Symbol::U8, SymbolType::KEYWORD },
    { Symbol::I8, SymbolType::KEYWORD },
    { Symbol::U16LE, SymbolType::KEYWORD },
    { Symbol::U16BE, SymbolType::KEYWORD },
    { Symbol::I16LE, SymbolType::KEYWORD },
    { Symbol::I16BE, SymbolType::KEYWORD },
    { Symbol::U32LE, SymbolType::KEYWORD },
    { Symbol::U32BE, SymbolType::KEYWORD },
    { Symbol::I32LE, SymbolType::KEYWORD },
    { Symbol::I32BE, SymbolType::KEYWORD },
    { Symbol::U64LE, SymbolType::KEYWORD },
    { Symbol::U64BE, SymbolType::KEYWORD },
    { Symbol::I64LE, SymbolType::KEYWORD },
    { Symbol::I64BE, SymbolType::KEYWORD },
    { Symbol::POISON, SymbolType::KEYWORD },
    { Symbol::ATOM, SymbolType::KEYWORD },
    { Symbol::RECORD, SymbolType::KEYWORD },
    { Symbol::ARRAY, SymbolType::KEYWORD },
    { Symbol::DEST, SymbolType::KEYWORD },
};

SymbolType sym_type(Symbol sym)
{
    try {
        return sym_types.at(sym);
    } catch (const std::out_of_range&) {
        throw InvariantViolation(
                "Lookup failed in sym_type(Symbol::"
                + std::string{ sym_to_debug_str(sym) } + ")");
    }
}

static const std::map<Symbol, poly::string_view> syms_to_debug_strs{
    { Symbol::NL, "NL" },
    { Symbol::SEMI, "SEMI" },
    { Symbol::COMMA, "COMMA" },
    { Symbol::PAREN_OPEN, "PAREN_OPEN" },
    { Symbol::PAREN_CLOSE, "PAREN_CLOSE" },
    { Symbol::CURLY_OPEN, "CURLY_OPEN" },
    { Symbol::CURLY_CLOSE, "CURLY_CLOSE" },
    { Symbol::SQUARE_OPEN, "SQUARE_OPEN" },
    { Symbol::SQUARE_CLOSE, "SQUARE_CLOSE" },

    { Symbol::DIE, "DIE" },
    { Symbol::EXPECT, "EXPECT" },
    { Symbol::CONSUME, "CONSUME" },
    { Symbol::SIZEOF, "SIZEOF" },
    { Symbol::SEND, "SEND" },
    { Symbol::ASSIGN, "ASSIGN" },

    { Symbol::ASSUME, "ASSUME" },
    { Symbol::MEMBER, "MEMBER" },
    { Symbol::BIND, "BIND" },

    { Symbol::NEG, "NEG" },

    { Symbol::EQ, "EQ" },
    { Symbol::NE, "NE" },
    { Symbol::ADD, "ADD" },
    { Symbol::SUB, "SUB" },
    { Symbol::MUL, "MUL" },
    { Symbol::DIV, "DIV" },
    { Symbol::MOD, "MOD" },

    { Symbol::BYTE, "BYTE" },
    { Symbol::U8, "U8" },
    { Symbol::I8, "I8" },
    { Symbol::U16LE, "U16LE" },
    { Symbol::U16BE, "U16BE" },
    { Symbol::I16LE, "I16LE" },
    { Symbol::I16BE, "I16BE" },
    { Symbol::U32LE, "U32LE" },
    { Symbol::U32BE, "U32BE" },
    { Symbol::I32LE, "I32LE" },
    { Symbol::I32BE, "I32BE" },
    { Symbol::U64LE, "U64LE" },
    { Symbol::U64BE, "U64BE" },
    { Symbol::I64LE, "I64LE" },
    { Symbol::I64BE, "I64BE" },
    { Symbol::POISON, "POISON" },
    { Symbol::ATOM, "ATOM" },
    { Symbol::RECORD, "RECORD" },
    { Symbol::ARRAY, "ARRAY" },
    { Symbol::DEST, "DEST" },
};

poly::string_view sym_to_debug_str(Symbol sym)
{
    try {
        return syms_to_debug_strs.at(sym);
    } catch (const std::out_of_range&) {
        static const poly::string_view unknown{ "UNKNOWN???" };
        return unknown;
        // throw InvariantViolation("Lookup failed in sym_to_debug_str()");
    }
}

/* non-static: this is exposed */
const std::vector<std::pair<poly::string_view, Symbol>> strs_to_syms{
    { ";", Symbol::SEMI },          { ",", Symbol::COMMA },
    { "(", Symbol::PAREN_OPEN },    { ")", Symbol::PAREN_CLOSE },
    { "{", Symbol::CURLY_OPEN },    { "}", Symbol::CURLY_CLOSE },
    { "[", Symbol::SQUARE_OPEN },   { "]", Symbol::SQUARE_CLOSE },
    { "==", Symbol::EQ },           { "!=", Symbol::NE },
    { "=", Symbol::ASSIGN },        { "+", Symbol::ADD },
    { "-", Symbol::SUB },           { "*", Symbol::MUL },
    { "/", Symbol::DIV },           { "%", Symbol::MOD },
    { ":", Symbol::ASSUME },        { ".", Symbol::MEMBER },
    { "die", Symbol::DIE },         { "expect", Symbol::EXPECT },
    { "consume", Symbol::CONSUME }, { "sizeof", Symbol::SIZEOF },
    { "sendto", Symbol::SEND },     { "Byte", Symbol::BYTE },
    { "UInt8", Symbol::U8 },        { "Int8", Symbol::I8 },
    { "UInt16LE", Symbol::U16LE },  { "UInt16BE", Symbol::U16BE },
    { "Int16LE", Symbol::I16LE },   { "Int16BE", Symbol::I16BE },
    { "UInt32LE", Symbol::U32LE },  { "UInt32BE", Symbol::U32BE },
    { "Int32LE", Symbol::I32LE },   { "Int32BE", Symbol::I32BE },
    { "UInt64LE", Symbol::U64LE },  { "UInt64BE", Symbol::U64BE },
    { "Int64LE", Symbol::I64LE },   { "Int64BE", Symbol::I64BE },
    { "Poison", Symbol::POISON },
};

/* These symbols can't actually be accessed via these names. */
static const std::vector<std::pair<poly::string_view, Symbol>>
        addl_strs_to_syms{
            { "\\n", Symbol::NL },        { "Atom", Symbol::ATOM },
            { "Record", Symbol::RECORD }, { "Array", Symbol::ARRAY },
            { "Dest", Symbol::DEST },     { "bind", Symbol::BIND },
            { "-", Symbol::NEG },
        };

static const std::map<Symbol, poly::string_view> syms_to_repr_strs{ []() {
    std::map<Symbol, poly::string_view> m;
    for (const auto& pair : strs_to_syms) {
        m.emplace(pair.second, pair.first);
    }
    for (const auto& pair : addl_strs_to_syms) {
        m.emplace(pair.second, pair.first);
    }
    return m;
}() };

poly::string_view sym_to_repr_str(Symbol sym)
{
    try {
        return syms_to_repr_strs.at(sym);
    } catch (const std::out_of_range&) {
        throw InvariantViolation(
                "Lookup failed in sym_to_repr_str(Symbol::"
                + std::string{ sym_to_debug_str(sym) } + ")");
    }
}

static const std::map<Symbol, poly::string_view> syms_to_ser_strs{
    { Symbol::EQ, "eq" },           { Symbol::NE, "ne" },
    { Symbol::ADD, "add" },         { Symbol::SUB, "sub" },
    { Symbol::MUL, "mul" },         { Symbol::DIV, "div" },
    { Symbol::MOD, "mod" },

    { Symbol::DIE, "die" },         { Symbol::EXPECT, "expect" },
    { Symbol::CONSUME, "consume" }, { Symbol::SIZEOF, "sizeof" },
    { Symbol::SEND, "send" },       { Symbol::ASSIGN, "assign" },
    { Symbol::ASSUME, "assume" },   { Symbol::MEMBER, "member" },
    { Symbol::BIND, "bind" },       { Symbol::NEG, "neg" },

    { Symbol::BYTE, "byte" },       { Symbol::U8, "u1" },
    { Symbol::I8, "i1" },           { Symbol::U16LE, "u2l" },
    { Symbol::U16BE, "u2b" },       { Symbol::I16LE, "i2l" },
    { Symbol::I16BE, "i2b" },       { Symbol::U32LE, "u4l" },
    { Symbol::U32BE, "u4b" },       { Symbol::I32LE, "i4l" },
    { Symbol::I32BE, "i4b" },       { Symbol::U64LE, "u8l" },
    { Symbol::U64BE, "u8b" },       { Symbol::I64LE, "i8l" },
    { Symbol::I64BE, "i8b" },

    { Symbol::POISON, "poison" },   { Symbol::ATOM, "atom" },
    { Symbol::RECORD, "record" },   { Symbol::ARRAY, "array" },
    { Symbol::DEST, "dest" },
};

poly::string_view sym_to_ser_str(Symbol sym)
{
    try {
        return syms_to_ser_strs.at(sym);
    } catch (const std::out_of_range&) {
        throw InvariantViolation(
                "Lookup failed in sym_to_ser_str(Symbol::"
                + std::string{ sym_to_debug_str(sym) } + ")");
    }
}

} // namespace openzl::sddl
