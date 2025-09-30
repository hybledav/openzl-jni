## Writing an SDDL Description

The Simple Data Description Language is a Domain-Specific Language that makes it easy to describe to OpenZL the components that make up simple data formats.

The fundamental task of an SDDL Description is to associate each byte of the input stream with a corresponding **Field**, whose purpose is to give OpenZL a hint for how to group and handle that part of the input.

The operative part of an SDDL Description, the part that actually constructs that association between a part of the input stream and a **Field**, is an operation called **consumption**.

When executing an SDDL Description over an input, the SDDL graph maintains an implicit cursor which starts at the beginning of the input. As the execution proceeds, and the description is applied to the input, each consumption operation associates the next byte(s) at the current cursor position with the consumed field and advances the cursor past those byte(s).

The task of the description is complete when the cursor reaches the end of the input. If the description ends and the whole input hasn't been consumed, or if the description tries to consume bytes past the end of the input, the execution will fail.

!!! example "Example SDDL Description"

    ```
    # Declares a header field, which is 8 bytes.
    Header = Byte[8]

    # Consume the header.
    # I.e., mark the first 8 bytes of the input as belonging to a generic
    # Byte field and advance the cursor past those 8 bytes.
    header : Header

    # Declare a compound field which consists of 4 bytes followed by an
    # integer field.
    Row = {
      Byte[4]
      UInt32LE
    }

    # Calculate how many copies of the Row fit in the remainder of the input.
    row_count = _rem / sizeof Row

    # Ensure that the row size evenly divides the remaining input.
    expect _rem % sizeof Row == 0

    # Consume the rest of the input as an array of Rows.
    body : Row[row_count]
    ```

The above example should give you an intuition for what an SDDL Description looks like. The following sections describe the syntax of the SDDL Description Language and its semantics in more rigorous detail.

!!! warning
    The SDDL Language is under active development. Its capabilities are expected to grow significantly. As part of that development, the syntax and semantics of existing features may change or break without warning.

An SDDL Description is a series of **Expressions**. At the top level of a description, these expressions are implicitly newline terminated but can also optionally be explicitly terminated with a semicolon.

There are multiple kinds of **Expressions**:

### Fields

A **Field** is a tool to identify the type of data stored in a part of the input as well as to group appearances of that type of data in the input.

Fields are declared either by instantiating a built-in field or by composing one or more existing fields into a compound field. Input content is associated with fields via the **consume** operation.

#### Built-In Fields

The following table enumerates the predefined fields available in SDDL:

<table>
  <tr>
    <th>Name</th>
    <th><pre>ZL_Type</pre></th>
    <th>Size</th>
    <th>Signed</th>
    <th>Endianness</th>
  </tr>

  <tr>
    <td><pre>Byte</pre></td>
    <td>Serial</td>
    <td>1</td>
    <td>No</td>
    <td>N/A</td>
  </tr>
  <tr>
    <td><pre>Int8</pre></td>
    <td>Numeric</td>
    <td>1</td>
    <td>Yes</td>
    <td>N/A</td>
  </tr>
  <tr>
    <td><pre>UInt8</pre></td>
    <td>Numeric</td>
    <td>1</td>
    <td>No</td>
    <td>N/A</td>
  </tr>

  <tr>
    <td><pre>Int16LE</pre></td>
    <td>Numeric</td>
    <td>2</td>
    <td>Yes</td>
    <td>Little</td>
  </tr>
  <tr>
    <td><pre>Int16BE</pre></td>
    <td>Numeric</td>
    <td>2</td>
    <td>Yes</td>
    <td>Big</td>
  </tr>
  <tr>
    <td><pre>UInt16LE</pre></td>
    <td>Numeric</td>
    <td>2</td>
    <td>No</td>
    <td>Little</td>
  </tr>
  <tr>
    <td><pre>UInt16BE</pre></td>
    <td>Numeric</td>
    <td>2</td>
    <td>No</td>
    <td>Big</td>
  </tr>

  <tr>
    <td><pre>Int32LE</pre></td>
    <td>Numeric</td>
    <td>4</td>
    <td>Yes</td>
    <td>Little</td>
  </tr>
  <tr>
    <td><pre>Int32BE</pre></td>
    <td>Numeric</td>
    <td>4</td>
    <td>Yes</td>
    <td>Big</td>
  </tr>
  <tr>
    <td><pre>UInt32LE</pre></td>
    <td>Numeric</td>
    <td>4</td>
    <td>No</td>
    <td>Little</td>
  </tr>
  <tr>
    <td><pre>UInt32BE</pre></td>
    <td>Numeric</td>
    <td>4</td>
    <td>No</td>
    <td>Big</td>
  </tr>

  <tr>
    <td><pre>Int64LE</pre></td>
    <td>Numeric</td>
    <td>8</td>
    <td>Yes</td>
    <td>Little</td>
  </tr>
  <tr>
    <td><pre>Int64BE</pre></td>
    <td>Numeric</td>
    <td>8</td>
    <td>Yes</td>
    <td>Big</td>
  </tr>
  <tr>
    <td><pre>UInt64LE</pre></td>
    <td>Numeric</td>
    <td>8</td>
    <td>No</td>
    <td>Little</td>
  </tr>
  <tr>
    <td><pre>UInt64BE</pre></td>
    <td>Numeric</td>
    <td>8</td>
    <td>No</td>
    <td>Big</td>
  </tr>
</table>

A consumption operation invoked on one of these field types will evaluate to the value of the bytes consumed, interpreted according to the type of the field. E.g., if the next 4 bytes of the input are `"\x01\x23\x45\x67"`, the expression `result : UInt32BE` will store the value 0x01234567 in `result`. `"\xff"` consumed as a `Int8` will produce -1 where if it were instead consumed as `UInt8` or `Byte` it would evaluate to 255.

#### Compound Fields

##### Arrays

An array is constructed from a field and a length:

```
Foo = Byte
len = 1234

ArrayOfFooFields = Foo[len]
```

Consuming an **Array** consumes the inner field a number of times, equal to the provided length of the array.

!!! Note
    The field and length are evaluated when the array is declared, not when it is used. E.g.,

    ```
    Foo = Byte
    len = 42

    Arr = Foo[len]

    Foo = UInt32LE
    len = 10

    : Arr
    ```

    This will consume 42 bytes, not 10 32-bit integers.

##### Records

A **Record** is a sequential collection of **Fields**. A Record is declared by listing its member fields as a comma-separated list between curly braces:

```
Row = {
  Byte,
  Byte,
  UInt32LE[8],
}
```

A member field of type `T` in a record can be expressed in the following three ways:

```
{
  T,       # Bare field, implies the consumption of the field
  : T,     # An instruction to consume the field, equivalent to the previous
  var : T, # Consumption of the field, with the result assigned to a variable
}
```

Consuming a Record expands to an in-order consumption of its member fields.

The return value of consuming a Record is a scope object, which contains variables captured during consumption. Fields' values will be captured into this returned scope when they are expressed in the `variable : Field` syntax. These values can be retrieved from the returned scope using the `.` member access operator.

!!! example
    ```
    Header = {
      magic : UInt32LE,
      size : UInt32LE,
    }

    hdr : Header

    expect hdr.magic == 1234567890
    contents : Contents[hdr.size]
    ```

    This example demonstrates the declaration, consumption, and then use of values of member fields of a Record.

#### Field Instances

Each field declaration instantiates a new field. Different instances of a field, even when they have otherwise identical properties, may be treated differently by the SDDL engine.

Each use of a built-in field name is considered a declaration. E.g.,

```
Foo = {
  UInt64LE
  UInt64LE
  UInt64LE
  UInt64LE
}
```

is different from

```
U64 = UInt64LE

Foo = {
  U64
  U64
  U64
  U64
}
```

In the former, four different integer fields are declared, whereas in the latter only one is.

In the future, we intend for the SDDL engine to make intelligent decisions about how to map each fields to output streams. For the moment, though, each field instance is mechanically given its own output stream. This means that the two examples above produce different parses of the input:

In the former, the content consumed by `Foo` will be mapped to four different output streams, whereas in the latter it will all be sent to a single output stream.

### Numbers

Other than **Fields**, the other value that SDDL manipulates is **Numbers**.

!!! warning
    All numbers in SDDL are signed 64-bit integers.

    Smaller types are sign-extended into 64-bit width. Unsigned 64-bit fields are converted to signed 64-bit values via twos-complement conversion.

**Numbers** arise from integer literals that appear in the description, as the result of evaluating arithmetic expressions, or as the result of consuming a numeric type.

### Operations

<table>
  <tr>
    <th>Operation</th>
    <th>Syntax</th>
    <th>Args</th>
    <th>Result</th>
    <th>Arg #1</th>
    <th>Arg #2</th>
    <th>Effect</th>
  </tr>
  <!-- <tr>
    <td>Die</td>
    <td><pre>die</pre></td>
    <td>0</td>
    <td><pre>N</pre></td>
    <td><pre></pre></td>
    <td><pre></pre></td>
    <td>Executing this operation unconditionally fails the run.</td>
  </tr> -->
  <tr>
    <td>Expect</td>
    <td><pre>expect ARG</pre></td>
    <td>1</td>
    <td><pre>N</pre></td>
    <td><pre>IV</pre></td>
    <td><pre></pre></td>
    <td>Fails the run if the argument is 0.</td>
  </tr>
  <tr>
    <td>Consume</td>
    <td><pre>ARG1? : ARG2</pre></td>
    <td>1-2</td>
    <td><pre>I?</pre></td>
    <td><pre>V?</pre></td>
    <td><pre>FV</pre></td>
    <td>Consumes the field provided as ARG2, stores the result into an optional variable ARG1. The expression as a whole also evaluates to that result value.</td>
  </tr>
  <tr>
    <td>Sizeof</td>
    <td><pre>sizeof ARG</pre></td>
    <td>1</td>
    <td><pre>I</pre></td>
    <td><pre>FV</pre></td>
    <td><pre></pre></td>
    <td>Evaluates to the size in bytes of the given field.</td>
  </tr>
  <tr>
    <td>Assign</td>
    <td><pre>ARG1 = ARG2</pre></td>
    <td>2</td>
    <td><pre>IF</pre></td>
    <td><pre>V</pre></td>
    <td><pre>IFV</pre></td>
    <td>Stores the resolved value of the expression in ARG2 and stores it in the variable ARG1. The assignment expression also evaluates as a whole to that resolved value.</td>
  </tr>
  <tr>
    <td>Member</td>
    <td><pre>ARG1.ARG2</pre></td>
    <td>2</td>
    <td><pre>IF</pre></td>
    <td><pre>SV</pre></td>
    <td><pre>V</pre></td>
    <td>Retrieves the value of variable ARG2 in scope ARG1. The result of the member access operator is the value, not the variable. This means that this result cannot be assigned to.</td>
  </tr>
  <tr>
    <td>Negate</td>
    <td><pre>- ARG1</pre></td>
    <td>1</td>
    <td><pre>I</pre></td>
    <td><pre>IV</pre></td>
    <td><pre></pre></td>
    <td>Negates the provided numeric argument.</td>
  </tr>
  <tr>
    <td>Equality</td>
    <td><pre>ARG1 == ARG2</pre></td>
    <td>2</td>
    <td><pre>I</pre></td>
    <td><pre>IV</pre></td>
    <td><pre>IV</pre></td>
    <td>Evaluates to 1 if integer arguments are equal, 0 if not.</td>
  </tr>
  <tr>
    <td>Inequality</td>
    <td><pre>ARG1 != ARG2</pre></td>
    <td>2</td>
    <td><pre>I</pre></td>
    <td><pre>IV</pre></td>
    <td><pre>IV</pre></td>
    <td>Evaluates to 0 if integer arguments are equal, 1 if not.</td>
  </tr>
  <tr>
    <td>Addition</td>
    <td><pre>ARG1 + ARG2</pre></td>
    <td>2</td>
    <td><pre>I</pre></td>
    <td><pre>IV</pre></td>
    <td><pre>IV</pre></td>
    <td>Returns the sum of the two integer arguments.</td>
  </tr>
  <tr>
    <td>Subtraction</td>
    <td><pre>ARG1 - ARG2</pre></td>
    <td>2</td>
    <td><pre>I</pre></td>
    <td><pre>IV</pre></td>
    <td><pre>IV</pre></td>
    <td>Returns the difference of the two integer arguments.</td>
  </tr>
  <tr>
    <td>Multiplication</td>
    <td><pre>ARG1 * ARG2</pre></td>
    <td>2</td>
    <td><pre>I</pre></td>
    <td><pre>IV</pre></td>
    <td><pre>IV</pre></td>
    <td>Returns the product of the two integer arguments.</td>
  </tr>
  <tr>
    <td>Division</td>
    <td><pre>ARG1 / ARG2</pre></td>
    <td>2</td>
    <td><pre>I</pre></td>
    <td><pre>IV</pre></td>
    <td><pre>IV</pre></td>
    <td>Returns the integer division of the two integer arguments. ARG2 must be non-zero.</td>
  </tr>
  <tr>
    <td>Remainder</td>
    <td><pre>ARG1 % ARG2</pre></td>
    <td>2</td>
    <td><pre>I</pre></td>
    <td><pre>IV</pre></td>
    <td><pre>IV</pre></td>
    <td>Returns the remainder of dividing the two integer arguments. ARG2 must be non-zero.</td>
  </tr>
</table>

???+ note "Evaluation Order"
    Note that unlike C, which largely avoids defining the relative order in
    which different parts of an expression are evaluated (instead, only
    adding a limited number of sequencing points), SDDL has a defined order
    in which the parts of an expression are evaluated:

    **For binary operators, the left-hand side is always evaluated before the right-hand side.**

    This means that the behavior of valid expressions is always well-defined.
    E.g.:

    ```
    foo = :UInt32LE + 2 * :Byte
    ```

    must sequence the 32-bit int before the byte. Of course, it's probably
    better to avoid relying on this.

#### Consuming Fields

##### Consuming Atomic Fields

Consuming an atomic **Field** of size `N` has the following effects:

1. The next `N` bytes of the input stream, starting at the current cursor position, are associated with the field being consumed. This means they will be dispatched into the output stream associated with this field.

2. The cursor is advanced `N` bytes.

3. Those bytes are interpreted according to the field type (type, signedness, endianness), and the consumption operation evaluates to that value.

##### Consuming Compound Fields

Consuming a compound **Field** is recursively expanded to be a consumption of the leaf atomic fields that comprise the compound **Field**.

Currently, the consumption of a compound field does not produce a value.

### Variables

A **Variable** holds a value, the result of evaluating an **Expression**.

Variable names begin with an alphabetical character or underscore and contain any number of subsequent underscores or alphanumeric characters.

Variables are assigned to via either the `=` operator or as part of the `:` operator:

```
var = 2 + 2
expect var == 4

# consumes the Byte field, and stores the value read from the field into var.
var : Byte
```

Other than when it appears on the left-hand side of an assignment operation, referring to a variable in an expression resolves to the value it contains.

<!-- !!! danger
    TODO: document variable scoping.

    (For now, they're all global.) -->

#### Special Variables

The SDDL engine exposes some information to the description environment via
the following implicit variables:

Name   |  Type  | Value
-------|--------|------
`_rem` | Number | The number of bytes remaining in the input.

These variables cannot be assigned to.
