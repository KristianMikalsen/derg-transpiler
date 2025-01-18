# Data structures

A program cannot hold state without a data type capable of storing information. Data structures are symbols which take
an arbitrary amount of space in memory, to store some information. Such information may represent some combination of
primitive units such as integers, floating points, boolean values, and/or other data structures.

Note that there is no difference between a primitive data structure such as an integer, and a user-defined data
structure. Typically, primitive data structures will not contain any extra functionality, only the semantics of the type
itself. User-defined data structures will ofter contain fields and methods, which may make them different, but there is
no conceptual difference.

Here is a few quick examples of how a data structure may be defined in code:

```derg
exported struct DivideByZeroError

private struct Point(val x: Float32, val y: Float32)

public struct List[Type](initial_capacity: Int64 = 8)
{
    var size = 0
    var capacity = initial_capacity
    var elements = allocate_heap_memory[Type](initial_capacity)
}
```

## Fields

Data structures are permitted to contain any number of *fields*, which is a binding to an inner data structure. Fields
may be used to build up sum types, where one data structure is the combination of one or more other data structures. By
combining multiple fields, it is possible to construct abstractions, which can encapsulate and hide state which should
be invisible from the rest of the world.

Fields are permitted to have a visibility modifier, which ensures that encapsulation can be enforced. Fields may be
accessed or modified only if the field is visible from the current scope.

When a user declares a field, the field must be given a mutability modifier. This modifier determines whether the field
can be bound to another instance, or whether it will remain unbindable throughout the lifetime of the data structure
instance. Any field marked as `var` can be rebound to some other value, whereas any field marked as `val` cannot be
rebound. Developers are highly encouraged to mark every field as `val`, and only resort to `var` when this is the only
option.

Once an instance of the data structure is instantiated, every field must have been given a value for the instance to be
valid. In the event that the data structure has multiple constructors, every field must be initialized exactly once in
every single constructor.

The order in which fields are initialized depends on the order in which they are declared in the data structure. Every
field must be initialized in the same order as they are declared. In turn, all fields are destructed in the opposite
order in which they were initialized. If a field has not yet been initialized, it cannot be read from or used in any way
aside from being assigned a value.

The field type may be inferred from an initialization expression if the field is immediately assigned a value. If the
assignment of the field is delayed to a secondary constructor, an expression may be omitted, but the developer must in
this case explicitly state what the type is.

The syntax for declaring a field is as follows:

```
$field := $visibility $binding $identifier $[: $type]? $[= $expression]?
```

Fields may be declared in the following manners (non-exhaustive list of examples):

```derg
public val field_a = 0
public val field_b: MyType
private var field_c: MyType = some_factory()
```

## Constructors

Data structures cannot be summoned into existence out of nowhere. In one way or another, the methods for instantiating a
new instance of the data structure must be defined. Constructors can be seen as factory functions, where they have the
sole purpose of ensuring the instance they attempt to produce is either fully constructed, or not constructed at all.

As such, constructors have additional limitations normal functions do not - all data structure fields must be
initialized before the constructor is permitted to return.

Constructors will always return the same type as the data structure they are declared within, but they are permitted to
raise errors. Raising errors is useful to communicate that the instance could not be constructed, which the client code
must respect and handle. Note that in the event the instance could not be constructed, any fields which were initialized
will be destructed in the opposite order in which they were initialized.

Constructors are only required when the data structure contains any fields. If there are no fields, constructors may be
fully omitted. When the data structure has no fields, it will be interpreted as some sort of object of which only a
single instance can exist instead. Such a field-less structure cannot be instantiated by the developer, but it can be
passed around as a value.

### Primary constructor

The primary constructor is the core way of defining how an instance of the data structure can be constructed. It behaves
in similar manners to regular functions, in the sense that it can take in any number of runtime parameters. However, it
cannot be given any compile-time parameters; these are instead declared on a data structure level. All compile-time
parameters declared on the structure itself are accessible to the constructor.

Unlike functions, however, the primary constructor may contain any number of field declarations as well. The order in
which parameters and fields are declared forms a synthetic function, which when invoked constructs an instance. This
function will respect default values, i.e. if a field is declared in the primary constructor with a value, this will be
the default value when invoking the function too. The names of the fields may be used as named parameters as well,
ensuring that constructors behave in much the same way as regular functions.

Note that unlike in the data structure body, any field declared in the primary constructor must specify which type it
is. Fields behave similar to a regular parameter in a function list, where the type of the parameter must also be
specified.

The visibility of the primary constructor may be omitted, in which case it takes the same visibility as the data
structure. Otherwise, the primary constructor can be given an explicit visibility, which may be wider than that of the
data structure itself. If the visibility is wider than the structure, it would be possible to instantiate an object of
the data structure, but it would not be possible to reference its type.

The primary constructor must appear immediately after the data structure itself has been declared, before the body of
the structure.

The syntax for declaring the primary constructor is as follows:

```derg
$constructor-primary := $[visibility]? ( $field-and-parameter-list )
```

A non-exhaustive collection of primary constructor examples is as follows:

```derg
(parameter: Int32)
(parameter: Int32 = 42, var field: Bool = false)
public (val field_a: Int32, val field_b: SomeType)
private (val field_a = 0, parameter_a: SomeType, var field_b: AnotherType)
```

### Secondary constructors.

Secondary constructors behave similar to functions, in the sense they can be parameterized with both compile-time and
run-time parameters. These constructors may take in any arbitrary number of parameters of either type.

// TODO: Write me.

## Destructors

Destructors are the opposite of constructors - these are functions which takes an object apart and deconstructs all its
components as necessary. Destroying an object ensures that all resources it used are released, and that its contents can
never be accessed again.

All data structures are required to have at least one destructor. Typically, the destructor will be generated
automatically by the compiler, and invoked whenever an object is destroyed. This destruction happens automatically
whenever an object leaves its scope without being moved.

A developer may sometimes want to guarantee that some functionality is invoked before an object is destroyed, however.
This can be achieved by defining a custom destructor, which is named and may take any number of parameters. Destructors
may also return values, or raise errors if necessary. However, regardless of how the destructor exits, either the object
is fully destroyed, or it is moved elsewhere for future destructing.

Once a developer defines a custom destructor, the data structure will have no more default destructor. This means that
an object of this data type cannot be automatically destroyed. As a consequence, the developer must manually invoke
exactly one of the custom destructors must be invoked, otherwise the program is considered malformed. Note that the
object does not have to be destroyed, provided it is moved out of the destructor.

Note that the visibility of a destructor may make it impossible for certain consumers to destroy an object. This allows
developers to enforce that a resource borrowed elsewhere is returned for destruction.

The syntax for declaring a destructor is as follows:

```
$destructor := $visibility dtor $identifier ( $parameter-list ) $[: $type]? $[-> $type]? { $statement-list }
```

A non-exhaustive collection of destructor examples is as follows:

```derg
exported dtor release()
{
    log.info("Object has been released")
}

public dtor commit() -> TransactionError
{
    database.commit() !: DatabaseCommitFailed(this) 
}
```

## Methods

// TODO: Write me.
