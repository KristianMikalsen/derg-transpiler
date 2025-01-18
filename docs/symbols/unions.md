# Unions

## Destructors

// TODO: Write me.

Details to keep in mind:

- The destructor of a union type cannot be automatically generated if one or more of the variants have a custom
  destructor. In such cases, the developer must specify a custom destructor for the union, as the union as well must be
  destructible.
