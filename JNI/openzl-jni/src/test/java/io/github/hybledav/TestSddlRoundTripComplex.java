package io.github.hybledav;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TestSddlRoundTripComplex {

    private static void assertRoundTrip(String sddlProgram, byte[] payload) {
        byte[] compiled = OpenZLSddl.compile(sddlProgram, true, 0);
        assertNotNull(compiled, "compiled SDDL bytecode");
        try (OpenZLCompressor compressor = new OpenZLCompressor()) {
            compressor.configureSddl(compiled);
            byte[] compressed = compressor.compress(payload);
            assertNotNull(compressed, "compressed output");
            byte[] restored = compressor.decompress(compressed);
            assertArrayEquals(payload, restored, "round-trip payload");
        }
    }

    private static byte[] iotaArray(int length) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = (byte) (i + 1);
        }
        return result;
    }

    @Test
    void roundTripAlternateFields() {
        String sddl = String.join("\n",
                "field_width = 4;",
                "Field1 = Byte[field_width];",
                "Field2 = Byte[field_width];",
                "Row = {",
                "    Field1;",
                "    Field2;",
                "};",
                "row_width = sizeof Row;",
                "input_size = _rem;",
                "row_count = input_size / row_width;",
                "",
                "# check row size evenly divides input",
                "expect input_size % row_width == 0;",
                "",
                "RowArray = Row[row_count];",
                ": RowArray;");

        String row = "12345678".repeat(8);
        byte[] payload = row.repeat(8).getBytes(StandardCharsets.US_ASCII);
        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripConsumeValues() {
    String sddl = String.join("\n",
        "B = Byte;",
        "I1L = Int8;",
        "I1B = Int8;",
        "U1L = UInt8;",
        "U1B = UInt8;",
        "I2L = Int16LE;",
        "I2B = Int16BE;",
        "U2L = UInt16LE;",
        "U2B = UInt16BE;",
        "I4L = Int32LE;",
        "I4B = Int32BE;",
        "U4L = UInt32LE;",
        "U4B = UInt32BE;",
        "I8L = Int64LE;",
        "I8B = Int64BE;",
        "U8L = UInt64LE;",
        "U8B = UInt64BE;",
        "",
        "expect (:B) == 1;",
        "expect (:B) == 254;",
        "",
        "expect (:I1L) == 1;",
        "expect (:I1L) == -2;",
        "expect (:I1B) == 1;",
        "expect (:I1B) == -2;",
        "expect (:U1L) == 1;",
        "expect (:U1L) == 239;",
        "expect (:U1B) == 1;",
        "expect (:U1B) == 239;",
        "expect (:I2L) == 291;",
        "expect (:I2L) == -292;",
        "expect (:I2B) == 291;",
        "expect (:I2B) == -292;",
        "expect (:U2L) == 291;",
        "expect (:U2L) == 61389;",
        "expect (:U2B) == 291;",
        "expect (:U2B) == 61389;",
        "expect (:I4L) == 19088743;",
        "expect (:I4L) == -19088744;",
        "expect (:I4B) == 19088743;",
        "expect (:I4B) == -19088744;",
        "expect (:U4L) == 19088743;",
        "expect (:U4L) == 4023233417;",
        "expect (:U4B) == 19088743;",
        "expect (:U4B) == 4023233417;",
        "expect (:I8L) == 81985529216486895;",
        "expect (:I8L) == -81985529216486896;",
        "expect (:I8B) == 81985529216486895;",
        "expect (:I8B) == -81985529216486896;",
        "expect (:U8L) == 81985529216486895;",
        "expect (:U8L) == 8056283915067138817;",
        "expect (:U8B) == 81985529216486895;",
        "expect (:U8B) == 8056283915067138817;");

        byte[] payload = new byte[] {
                0x01,
                (byte) 0xFE,
                0x01,
                (byte) 0xFE,
                0x01,
                (byte) 0xFE,
                0x01,
                (byte) 0xEF,
                0x01,
                (byte) 0xEF,
                0x23, 0x01,
                (byte) 0xDC, (byte) 0xFE,
                0x01, 0x23,
                (byte) 0xFE, (byte) 0xDC,
                0x23, 0x01,
                (byte) 0xCD, (byte) 0xEF,
                0x01, 0x23,
                (byte) 0xEF, (byte) 0xCD,
                0x67, 0x45, 0x23, 0x01,
                (byte) 0x98, (byte) 0xBA, (byte) 0xDC, (byte) 0xFE,
                0x01, 0x23, 0x45, 0x67,
                (byte) 0xFE, (byte) 0xDC, (byte) 0xBA, (byte) 0x98,
                0x67, 0x45, 0x23, 0x01,
                (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
                0x01, 0x23, 0x45, 0x67,
                (byte) 0xEF, (byte) 0xCD, (byte) 0xAB, (byte) 0x89,
                (byte) 0xEF, (byte) 0xCD, (byte) 0xAB, (byte) 0x89, 0x67, 0x45, 0x23, 0x01,
                0x10, 0x32, 0x54, 0x76, (byte) 0x98, (byte) 0xBA, (byte) 0xDC, (byte) 0xFE,
                0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
                (byte) 0xFE, (byte) 0xDC, (byte) 0xBA, (byte) 0x98, 0x76, 0x54, 0x32, 0x10,
                (byte) 0xEF, (byte) 0xCD, (byte) 0xAB, (byte) 0x89, 0x67, 0x45, 0x23, 0x01,
                0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, 0x6F,
                0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF,
                0x6F, (byte) 0xCD, (byte) 0xAB, (byte) 0x89, 0x67, 0x45, 0x23, 0x01 };

        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripConsumeFloats() {
    String sddl = String.join("\n",
        "F1 = Float8;",
        "F2L = Float16LE;",
        "F2B = Float16BE;",
        "F4L = Float32LE;",
        "F4B = Float32BE;",
        "F8L = Float64LE;",
        "F8B = Float64BE;",
        "BF1 = BFloat8;",
        "BF2L = BFloat16LE;",
        "BF2B = BFloat16BE;",
        "BF4L = BFloat32LE;",
        "BF4B = BFloat32BE;",
        "BF8L = BFloat64LE;",
        "BF8B = BFloat64BE;",
        "",
        "expect sizeof F1 == 1;",
        "expect sizeof F2L == 2;",
        "expect sizeof F2B == 2;",
        "expect sizeof F4L == 4;",
        "expect sizeof F4B == 4;",
        "expect sizeof F8L == 8;",
        "expect sizeof F8B == 8;",
        "expect sizeof BF1 == 1;",
        "expect sizeof BF2L == 2;",
        "expect sizeof BF2B == 2;",
        "expect sizeof BF4L == 4;",
        "expect sizeof BF4B == 4;",
        "expect sizeof BF8L == 8;",
        "expect sizeof BF8B == 8;",
        "",
        ": F1;",
        ": F2L;",
        ": F2B;",
        ": F4L;",
        ": F4B;",
        ": F8L;",
        ": F8B;",
        ": BF1;",
        ": BF2L;",
        ": BF2B;",
        ": BF4L;",
        ": BF4B;",
        ": BF8L;",
        ": BF8B;");

        byte[] payload = iotaArray(58);
        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripArithmeticProgram() {
    String sddl = String.join("\n",
        "expect 5 + 10 == 15;",
        "expect -5 + 10 == 5;",
        "expect 5 + -10 == -5;",
        "expect -5 + -10 == -15;",
        "",
        "expect 5 - 10 == -5;",
        "expect 10 - 5 == 5;",
        "expect -10 - 5 == -15;",
        "expect 10 - -5 == 15;",
        "expect -10 - -5 == -5;",
        "",
        "expect 5 * 10 == 50;",
        "",
        "expect 73 / 10 == 7;",
        "expect 73 % 10 == 3;",
        "",
        "expect 10 == 10;",
        "expect 10 == 9 == 0;",
        "",
        "expect 10 != 9;",
        "expect 10 != 10 == 0;",
        "",
        "expect 10 > 9;",
        "expect 10 > 10 == 0;",
        "expect 10 > 11 == 0;",
        "expect 10 >= 9;",
        "expect 10 >= 10;",
        "expect 10 >= 11 == 0;",
        "expect 10 < 9 == 0;",
        "expect 10 < 10 == 0;",
        "expect 10 < 11;",
        "expect 10 <= 9 == 0;",
        "expect 10 <= 10;",
        "expect 10 <= 11;",
        "",
        ": Byte[_rem];");

        byte[] payload = iotaArray(10);
        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripMildlyVexingParses() {
    String sddl = String.join("\n",
        "B = Byte;",
        "b : B;",
        "expect b == 1;",
        "",
        "b = -:B;",
        "expect b == -2;",
        "",
        ": B;",
        "",
        "B2 = Byte;",
        "b : B2;",
        "expect b == 4;",
        "",
        ": B;",
        "b : B;",
        "expect b == 6;",
        "",
        ": Byte;",
        "len = :Byte;",
        "Arr = Byte[len];",
        ": Arr;");

        byte[] payload = new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                0x02,
                0x01, 0x02 };
        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripExpressionEvaluationOrder() {
    String sddl = String.join("\n",
        "expect :UInt16LE + :UInt16BE + :Byte == :Byte;",
        "expect _rem == 0;");

        byte[] payload = new byte[] { 0x01, 0x00, 0x00, 0x02, 0x03, 0x06 };
        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripRecordsWithNamedFields() {
    String sddl = String.join("\n",
        "Foo = {",
        "    Byte;",
        "    a : Byte;",
        "    : Byte;",
        "    b : Byte;",
        "};",
        "",
        "foo : Foo;",
        "",
        "expect foo.a == 2;",
        "expect foo.b == 4;");

        byte[] payload = new byte[] { 1, 2, 3, 4 };
        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripFunctionTemplate() {
    String sddl = String.join("\n",
        "func = (arg1, arg2) {",
        "    : Byte[arg1];",
        "    a : Byte;",
        "    : Byte[arg2];",
        "    b : Byte;",
        "};",
        "",
        "foo : func(1, 1);",
        "bar : func(0, 2);",
        "",
        "expect foo.a == 2;",
        "expect foo.b == 4;",
        "expect bar.a == 5;",
        "expect bar.b == 8;");

        byte[] payload = iotaArray(8);
        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripFunctionPartialApplication() {
    String sddl = String.join("\n",
        "func = (arg1, arg2) {",
        "    : Byte[arg1];",
        "    a : Byte;",
        "    : Byte[arg2];",
        "    b : Byte;",
        "};",
        "",
        "partial_1 = func(1);",
        "partial_0 = func(0);",
        "",
        "partial_1_1 = partial_1(1);",
        "partial_0_2 = partial_0(2);",
        "",
        "foo : partial_1_1();",
        "",
        "# with no new args to bind, the parens are actually unnecessary",
        "bar : partial_0_2;",
        "",
        "expect foo.a == 2;",
        "expect foo.b == 4;",
        "expect bar.a == 5;",
        "expect bar.b == 8;");

        byte[] payload = iotaArray(8);
        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripFunctionArgumentLifetimes() {
    String sddl = String.join("\n",
        "f = (m, n) {",
        "    : Byte[m];",
        "    : Byte[n];",
        "    val : Byte;",
        "};",
        "",
        "g = (f, n) {",
        "    r : f(n);",
        "};",
        "",
        "m = 1;",
        "n = 1;",
        "",
        "h = g(f(m), n);",
        "",
        "g = 0;",
        "f = 0;",
        "",
        "r : h;",
        "",
        "expect r.r.val == m + n + 1;");

        byte[] payload = iotaArray(3);
        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripAvoidScopeCopies() {
    String sddl = "f : (a1, a2, a3, a4, a5) { : Byte; } (1)(2)(3)(4)(5);";

        byte[] payload = iotaArray(1);
        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripAggregateDeclarations() {
    String sddl = String.join("\n",
        ": {}[1][1];",
        ": {Byte}[1][1];",
        ": {{Byte}}[1][1];");

        byte[] payload = iotaArray(2);
        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripNestedLengthPrefixedRecords() {
    String sddl = String.join("\n",
        "Header = {",
        "    count : UInt16LE;",
        "    total_len : UInt16LE;",
        "};",
        "",
        "header : Header;",
        "expect header.count == 2;",
        "expect header.total_len == 14;",
        "",
        "entry1_len : UInt8;",
        "expect entry1_len == 5;",
        "entry1_values : Byte[5];",
        "entry1_checksum : UInt16LE;",
        "expect entry1_checksum == 336;",
        "",
        "entry2_len : UInt8;",
        "expect entry2_len == 3;",
        "entry2_values : Byte[3];",
        "entry2_checksum : UInt16LE;",
        "expect entry2_checksum == 660;",
        "",
        "expect _rem == 0;");

        byte[] payload = new byte[] {
                0x02, 0x00,
                0x0E, 0x00,
                0x05,
                0x10, 0x20, 0x30, 0x40, 0x50,
                0x50, 0x01,
                0x03,
                (byte) 0xAA, (byte) 0xBB, (byte) 0xCC,
                (byte) 0x94, 0x02
        };

        assertRoundTrip(sddl, payload);
    }

    @Test
    void roundTripSensorLog() {
    String sddl = String.join("\n",
        "Header = {",
        "    version : UInt8;",
        "    timestamp : UInt32LE;",
        "    entry_count : UInt8;",
        "};",
        "",
        "header : Header;",
        "expect header.version == 1;",
        "expect header.timestamp == 305419896;",
        "expect header.entry_count == 2;",
        "",
        "entry1_id : UInt16BE;",
        "expect entry1_id == 4660;",
        "entry1_flags : UInt8;",
        "expect entry1_flags == 165;",
        "entry1_value_a : Int32LE;",
        "expect entry1_value_a == 16909060;",
        "entry1_value_b : Int32BE;",
        "expect entry1_value_b == 84281096;",
        "entry1_sample_count : UInt8;",
        "expect entry1_sample_count == 3;",
        "entry1_samples : Int16LE[3];",
        "entry1_comment_len : UInt8;",
        "expect entry1_comment_len == 4;",
        "entry1_comment : Byte[4];",
        "",
        "entry2_id : UInt16BE;",
        "expect entry2_id == 43981;",
        "entry2_flags : UInt8;",
        "expect entry2_flags == 90;",
        "entry2_value_a : Int32LE;",
        "expect entry2_value_a == -123456789;",
        "entry2_value_b : Int32BE;",
        "expect entry2_value_b == 287454020;",
        "entry2_sample_count : UInt8;",
        "expect entry2_sample_count == 2;",
        "entry2_samples : Int16LE[2];",
        "entry2_comment_len : UInt8;",
        "expect entry2_comment_len == 6;",
        "entry2_comment : Byte[6];",
        "",
        "expect _rem == 0;");

        byte[] payload = new byte[] {
                0x01,
                0x78, 0x56, 0x34, 0x12,
                0x02,
                0x12, 0x34,
                (byte) 0xA5,
                0x04, 0x03, 0x02, 0x01,
                0x05, 0x06, 0x07, 0x08,
                0x03,
                (byte) 0xE8, 0x03,
                0x30, (byte) 0xF8,
                0x2A, 0x00,
                0x04,
                0x4C, 0x49, 0x44, 0x31,
                (byte) 0xAB, (byte) 0xCD,
                0x5A,
                (byte) 0xEB, 0x32, (byte) 0xA4, (byte) 0xF8,
                0x11, 0x22, 0x33, 0x44,
                0x02,
                (byte) 0xFF, 0x7F,
                (byte) 0xFF, (byte) 0xFF,
                0x06,
                0x54, 0x45, 0x4D, 0x50, 0x34, 0x32
        };

        assertRoundTrip(sddl, payload);
    }
}