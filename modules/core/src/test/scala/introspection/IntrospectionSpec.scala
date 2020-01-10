// Copyright (c) 2016-2019 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package introspection

import cats.tests.CatsSuite
import io.circe.literal.JsonStringContext

import edu.gemini.grackle._

final class IntrospectionSuite extends CatsSuite {
  test("simple type query") {
    val text = """
      {
        __type(name: "User") {
          name
          fields {
            name
            type {
              name
            }
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "__type": {
            "name": "User",
            "fields": [
              {
                "name": "id",
                "type": { "name": "String" }
              },
              {
                "name": "name",
                "type": { "name": "String" }
              },
              {
                "name": "birthday",
                "type": { "name": "Date" }
              }
            ]
          }
        }
      }
    """

    val compiled = IntrospectionQueryCompiler.compile(text)
    val res = IntrospectionQueryInterpreter(TestSchema).run(compiled.right.get, SchemaSchema.queryType)
    //println(res)
    assert(res == expected)
  }

  test("kind/name/description/ofType query") {
    val text = """
      {
        __type(name: "KindTest") {
          name
          description
          fields {
            name
            description
            type {
              kind
              name
              description
              ofType {
                kind
                name
                description
                ofType {
                  kind
                  name
                  description
                  ofType {
                    kind
                    name
                    description
                  }
                }
              }
            }
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "__type" : {
            "name" : "KindTest",
            "description" : null,
            "fields" : [
              {
                "name" : "scalar",
                "description" : "Scalar field",
                "type" : {
                  "kind" : "SCALAR",
                  "name" : "Int",
                  "description" : "The Int scalar type represents a signed 32‐bit numeric non‐fractional value.\nResponse formats that support a 32‐bit integer or a number type should use that\ntype to represent this scalar.",
                  "ofType" : null
                }
              },
              {
                "name" : "object",
                "description" : "Object field",
                "type" : {
                  "kind" : "OBJECT",
                  "name" : "User",
                  "description" : "User object type",
                  "ofType" : null
                }
              },
              {
                "name" : "interface",
                "description" : "Interface field",
                "type" : {
                  "kind" : "INTERFACE",
                  "name" : "Profile",
                  "description" : "Profile interface type",
                  "ofType" : null
                }
              },
              {
                "name" : "union",
                "description" : "Union field",
                "type" : {
                  "kind" : "UNION",
                  "name" : "Choice",
                  "description" : "Choice union type",
                  "ofType" : null
                }
              },
              {
                "name" : "enum",
                "description" : "Enum field",
                "type" : {
                  "kind" : "ENUM",
                  "name" : "Flags",
                  "description" : "Flags enum type",
                  "ofType" : null
                }
              },
              {
                "name" : "list",
                "description" : "List field",
                "type" : {
                  "kind" : "LIST",
                  "name" : null,
                  "description" : null,
                  "ofType" : {
                    "kind" : "SCALAR",
                    "name" : "Int",
                    "description" : "The Int scalar type represents a signed 32‐bit numeric non‐fractional value.\nResponse formats that support a 32‐bit integer or a number type should use that\ntype to represent this scalar.",
                    "ofType" : null
                  }
                }
              },
              {
                "name" : "nonnull",
                "description" : "Nonnull field",
                "type" : {
                  "kind" : "NON_NULL",
                  "name" : null,
                  "description" : null,
                  "ofType" : {
                    "kind" : "SCALAR",
                    "name" : "Int",
                    "description" : "The Int scalar type represents a signed 32‐bit numeric non‐fractional value.\nResponse formats that support a 32‐bit integer or a number type should use that\ntype to represent this scalar.",
                    "ofType" : null
                  }
                }
              },
              {
                "name" : "nonnulllistnonnull",
                "description" : "Nonnull list of nonnull field",
                "type" : {
                  "kind" : "NON_NULL",
                  "name" : null,
                  "description" : null,
                  "ofType" : {
                    "kind" : "LIST",
                    "name" : null,
                    "description" : null,
                    "ofType" : {
                      "kind" : "NON_NULL",
                      "name" : null,
                      "description" : null,
                      "ofType" : {
                        "kind" : "SCALAR",
                        "name" : "Int",
                        "description" : "The Int scalar type represents a signed 32‐bit numeric non‐fractional value.\nResponse formats that support a 32‐bit integer or a number type should use that\ntype to represent this scalar."
                      }
                    }
                  }
                }
              }
            ]
          }
        }
      }
    """

    val compiled = IntrospectionQueryCompiler.compile(text)
    val res = IntrospectionQueryInterpreter(TestSchema).run(compiled.right.get, SchemaSchema.queryType)
    //println(res)
    assert(res == expected)
  }

  test("fields query") {
    val text = """
      {
        __type(name: "KindTest") {
          name
          fields {
            name
            type {
              name
              fields {
                name
                type {
                  name
                }
              }
            }
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "__type" : {
            "name" : "KindTest",
            "fields" : [
              {
                "name" : "scalar",
                "type" : {
                  "name" : "Int",
                  "fields" : null
                }
              },
              {
                "name" : "object",
                "type" : {
                  "name" : "User",
                  "fields" : [
                    {
                      "name" : "id",
                      "type" : {
                        "name" : "String"
                      }
                    },
                    {
                      "name" : "name",
                      "type" : {
                        "name" : "String"
                      }
                    },
                    {
                      "name" : "birthday",
                      "type" : {
                        "name" : "Date"
                      }
                    }
                  ]
                }
              },
              {
                "name" : "interface",
                "type" : {
                  "name" : "Profile",
                  "fields" : [
                    {
                      "name" : "id",
                      "type" : {
                        "name" : "String"
                      }
                    }
                  ]
                }
              },
              {
                "name" : "union",
                "type" : {
                  "name" : "Choice",
                  "fields" : null
                }
              },
              {
                "name" : "enum",
                "type" : {
                  "name" : "Flags",
                  "fields" : null
                }
              },
              {
                "name" : "list",
                "type" : {
                  "name" : null,
                  "fields" : null
                }
              },
              {
                "name" : "nonnull",
                "type" : {
                  "name" : null,
                  "fields" : null
                }
              },
              {
                "name" : "nonnulllistnonnull",
                "type" : {
                  "name" : null,
                  "fields" : null
                }
              }
            ]
          }
        }
      }
    """

    val compiled = IntrospectionQueryCompiler.compile(text)
    val res = IntrospectionQueryInterpreter(TestSchema).run(compiled.right.get, SchemaSchema.queryType)
    //println(res)
    assert(res == expected)
  }

  test("interfaces query") {
    val text = """
      {
        __type(name: "KindTest") {
          name
          fields {
            name
            type {
              interfaces {
                name
              }
            }
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "__type" : {
            "name" : "KindTest",
            "fields" : [
              {
                "name" : "scalar",
                "type" : {
                  "interfaces" : null
                }
              },
              {
                "name" : "object",
                "type" : {
                  "interfaces" : [
                    {
                      "name" : "Profile"
                    }
                  ]
                }
              },
              {
                "name" : "interface",
                "type" : {
                  "interfaces" : null
                }
              },
              {
                "name" : "union",
                "type" : {
                  "interfaces" : null
                }
              },
              {
                "name" : "enum",
                "type" : {
                  "interfaces" : null
                }
              },
              {
                "name" : "list",
                "type" : {
                  "interfaces" : null
                }
              },
              {
                "name" : "nonnull",
                "type" : {
                  "interfaces" : null
                }
              },
              {
                "name" : "nonnulllistnonnull",
                "type" : {
                  "interfaces" : null
                }
              }
            ]
          }
        }
      }
    """

    val compiled = IntrospectionQueryCompiler.compile(text)
    val res = IntrospectionQueryInterpreter(TestSchema).run(compiled.right.get, SchemaSchema.queryType)
    //println(res)
    assert(res == expected)
  }

  test("possibleTypes query") {
    val text = """
      {
        __type(name: "KindTest") {
          name
          fields {
            name
            type {
              possibleTypes {
                name
              }
            }
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "__type" : {
            "name" : "KindTest",
            "fields" : [
              {
                "name" : "scalar",
                "type" : {
                  "possibleTypes" : null
                }
              },
              {
                "name" : "object",
                "type" : {
                  "possibleTypes" : null
                }
              },
              {
                "name" : "interface",
                "type" : {
                  "possibleTypes" : [
                    {
                      "name" : "User"
                    }
                  ]
                }
              },
              {
                "name" : "union",
                "type" : {
                  "possibleTypes" : [
                    {
                      "name" : "User"
                    },
                    {
                      "name" : "Date"
                    }
                  ]
                }
              },
              {
                "name" : "enum",
                "type" : {
                  "possibleTypes" : null
                }
              },
              {
                "name" : "list",
                "type" : {
                  "possibleTypes" : null
                }
              },
              {
                "name" : "nonnull",
                "type" : {
                  "possibleTypes" : null
                }
              },
              {
                "name" : "nonnulllistnonnull",
                "type" : {
                  "possibleTypes" : null
                }
              }
            ]
          }
        }
      }
    """

    val compiled = IntrospectionQueryCompiler.compile(text)
    val res = IntrospectionQueryInterpreter(TestSchema).run(compiled.right.get, SchemaSchema.queryType)
    //println(res)
    assert(res == expected)
  }

  test("enumValues query") {
    val text = """
      {
        __type(name: "KindTest") {
          name
          fields {
            name
            type {
              enumValues {
                name
              }
            }
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "__type" : {
            "name" : "KindTest",
            "fields" : [
              {
                "name" : "scalar",
                "type" : {
                  "enumValues" : null
                }
              },
              {
                "name" : "object",
                "type" : {
                  "enumValues" : null
                }
              },
              {
                "name" : "interface",
                "type" : {
                  "enumValues" : null
                }
              },
              {
                "name" : "union",
                "type" : {
                  "enumValues" : null
                }
              },
              {
                "name" : "enum",
                "type" : {
                  "enumValues" : [
                    {
                      "name" : "A"
                    },
                    {
                      "name" : "C"
                    }
                  ]
                }
              },
              {
                "name" : "list",
                "type" : {
                  "enumValues" : null
                }
              },
              {
                "name" : "nonnull",
                "type" : {
                  "enumValues" : null
                }
              },
              {
                "name" : "nonnulllistnonnull",
                "type" : {
                  "enumValues" : null
                }
              }
            ]
          }
        }
      }
    """

    val compiled = IntrospectionQueryCompiler.compile(text)
    val res = IntrospectionQueryInterpreter(TestSchema).run(compiled.right.get, SchemaSchema.queryType)
    //println(res)
    assert(res == expected)
  }

  test("deprecation excluded") {
    val text = """
      {
        __type(name: "DeprecationTest") {
          fields {
            name
            type {
              fields(includeDeprecated: false) {
                name
                isDeprecated
                deprecationReason
              }
              enumValues(includeDeprecated: false) {
                name
                isDeprecated
                deprecationReason
              }
            }
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "__type" : {
            "fields" : [
              {
                "name" : "user",
                "type" : {
                  "fields" : [
                    {
                      "name" : "id",
                      "isDeprecated" : false,
                      "deprecationReason" : null
                    },
                    {
                      "name" : "name",
                      "isDeprecated" : false,
                      "deprecationReason" : null
                    },
                    {
                      "name" : "birthday",
                      "isDeprecated" : false,
                      "deprecationReason" : null
                    }
                  ],
                  "enumValues" : null
                }
              },
              {
                "name" : "flags",
                "type" : {
                  "fields" : null,
                  "enumValues" : [
                    {
                      "name" : "A",
                      "isDeprecated" : false,
                      "deprecationReason" : null
                    },
                    {
                      "name" : "C",
                      "isDeprecated" : false,
                      "deprecationReason" : null
                    }
                  ]
                }
              }
            ]
          }
        }
      }
    """

    val compiled = IntrospectionQueryCompiler.compile(text)
    val res = IntrospectionQueryInterpreter(TestSchema).run(compiled.right.get, SchemaSchema.queryType)
    //println(res)
    assert(res == expected)
  }

  test("deprecation included") {
    val text = """
      {
        __type(name: "DeprecationTest") {
          fields {
            name
            type {
              fields(includeDeprecated: true) {
                name
                isDeprecated
                deprecationReason
              }
              enumValues(includeDeprecated: true) {
                name
                isDeprecated
                deprecationReason
              }
            }
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "__type" : {
            "fields" : [
              {
                "name" : "user",
                "type" : {
                  "fields" : [
                    {
                      "name" : "id",
                      "isDeprecated" : false,
                      "deprecationReason" : null
                    },
                    {
                      "name" : "name",
                      "isDeprecated" : false,
                      "deprecationReason" : null
                    },
                    {
                      "name" : "age",
                      "isDeprecated" : true,
                      "deprecationReason" : "Use birthday instead"
                    },
                    {
                      "name" : "birthday",
                      "isDeprecated" : false,
                      "deprecationReason" : null
                    }
                  ],
                  "enumValues" : null
                }
              },
              {
                "name" : "flags",
                "type" : {
                  "fields" : null,
                  "enumValues" : [
                    {
                      "name" : "A",
                      "isDeprecated" : false,
                      "deprecationReason" : null
                    },
                    {
                      "name" : "B",
                      "isDeprecated" : true,
                      "deprecationReason" : "Deprecation test"
                    },
                    {
                      "name" : "C",
                      "isDeprecated" : false,
                      "deprecationReason" : null
                    }
                  ]
                }
              }
            ]
          }
        }
      }
    """

    val compiled = IntrospectionQueryCompiler.compile(text)
    val res = IntrospectionQueryInterpreter(TestSchema).run(compiled.right.get, SchemaSchema.queryType)
    //println(res)
    assert(res == expected)
  }

  test("simple schema query") {
    val text = """
      {
        __schema {
          queryType { name }
          mutationType { name }
          subscriptionType { name }
          types { name }
          directives { name }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "__schema" : {
            "queryType" : {
              "name" : "Query"
            },
            "mutationType" : null,
            "subscriptionType" : null,
            "types" : [
              {
                "name" : "Query"
              },
              {
                "name" : "User"
              },
              {
                "name" : "Profile"
              },
              {
                "name" : "Date"
              },
              {
                "name" : "Choice"
              },
              {
                "name" : "Flags"
              },
              {
                "name" : "KindTest"
              },
              {
                "name" : "DeprecationTest"
              }
            ],
            "directives" : [
            ]
          }
        }
      }
    """

    val compiled = IntrospectionQueryCompiler.compile(text)
    val res = IntrospectionQueryInterpreter(TestSchema).run(compiled.right.get, SchemaSchema.queryType)
    //println(res)
    assert(res == expected)
  }

  test("standard introspection query") {
    val text = """
      |query IntrospectionQuery {
      |  __schema {
      |    queryType { name }
      |    mutationType { name }
      |    subscriptionType { name }
      |    types {
      |      ...FullType
      |    }
      |    directives {
      |      name
      |      description
      |      locations
      |      args {
      |        ...InputValue
      |      }
      |    }
      |  }
      |}
      |
      |fragment FullType on __Type {
      |  kind
      |  name
      |  description
      |  fields(includeDeprecated: true) {
      |    name
      |    description
      |    args {
      |      ...InputValue
      |    }
      |    type {
      |      ...TypeRef
      |    }
      |    isDeprecated
      |    deprecationReason
      |  }
      |  inputFields {
      |    ...InputValue
      |  }
      |  interfaces {
      |    ...TypeRef
      |  }
      |  enumValues(includeDeprecated: true) {
      |    name
      |    description
      |    isDeprecated
      |    deprecationReason
      |  }
      |  possibleTypes {
      |    ...TypeRef
      |  }
      |}
      |
      |fragment InputValue on __InputValue {
      |  name
      |  description
      |  type { ...TypeRef }
      |  defaultValue
      |}
      |
      |fragment TypeRef on __Type {
      |  kind
      |  name
      |  ofType {
      |    kind
      |    name
      |    ofType {
      |      kind
      |      name
      |      ofType {
      |        kind
      |        name
      |        ofType {
      |          kind
      |          name
      |          ofType {
      |            kind
      |            name
      |            ofType {
      |              kind
      |              name
      |              ofType {
      |                kind
      |                name
      |              }
      |            }
      |          }
      |        }
      |      }
      |    }
      |  }
      |}
    """.stripMargin.trim

    val expected = json"""
      {
        "data" : {
          "__schema" : {
            "queryType" : {
              "name" : "Query"
            },
            "mutationType" : null,
            "subscriptionType" : null,
            "types" : [
              {
                "kind" : "OBJECT",
                "name" : "Query",
                "description" : null,
                "fields" : [
                  {
                    "name" : "users",
                    "description" : null,
                    "args" : [
                    ],
                    "type" : {
                      "kind" : "NON_NULL",
                      "name" : null,
                      "ofType" : {
                        "kind" : "LIST",
                        "name" : null,
                        "ofType" : {
                          "kind" : "NON_NULL",
                          "name" : null,
                          "ofType" : {
                            "kind" : "OBJECT",
                            "name" : "User",
                            "ofType" : null
                          }
                        }
                      }
                    },
                    "isDeprecated" : false,
                    "deprecationReason" : null
                  }
                ],
                "inputFields" : null,
                "interfaces" : [
                ],
                "enumValues" : null,
                "possibleTypes" : null
              },
              {
                "kind" : "OBJECT",
                "name" : "User",
                "description" : "User object type",
                "fields" : [
                  {
                    "name" : "id",
                    "description" : null,
                    "args" : [
                    ],
                    "type" : {
                      "kind" : "SCALAR",
                      "name" : "String",
                      "ofType" : null
                    },
                    "isDeprecated" : false,
                    "deprecationReason" : null
                  },
                  {
                    "name" : "name",
                    "description" : null,
                    "args" : [
                    ],
                    "type" : {
                      "kind" : "SCALAR",
                      "name" : "String",
                      "ofType" : null
                    },
                    "isDeprecated" : false,
                    "deprecationReason" : null
                  },
                  {
                    "name" : "age",
                    "description" : null,
                    "args" : [
                    ],
                    "type" : {
                      "kind" : "SCALAR",
                      "name" : "Int",
                      "ofType" : null
                    },
                    "isDeprecated" : false,
                    "deprecationReason" : null
                  }
                ],
                "inputFields" : null,
                "interfaces" : [
                ],
                "enumValues" : null,
                "possibleTypes" : null
              }
            ],
            "directives" : [
            ]
          }
        }
      }
    """

    val compiled = IntrospectionQueryCompiler.compile(text)
    val res = IntrospectionQueryInterpreter(SmallSchema).run(compiled.right.get, SchemaSchema.queryType)
    //println(res)
    assert(res == expected)
  }
}

object TestSchema extends Schema {
  import ScalarType._

  val types = List(
    ObjectType(
      name = "Query",
      description = None,
      fields = List(
        Field("users", None, Nil, ListType(TypeRef("User")), false, None)
      ),
      interfaces = Nil
    ),
    ObjectType(
      name = "User",
      description = Some("User object type"),
      fields = List(
        Field("id", None, Nil, NullableType(StringType), false, None),
        Field("name", None, Nil, NullableType(StringType), false, None),
        Field("age", None, Nil, NullableType(IntType), true, Some("Use birthday instead")),
        Field("birthday", None, Nil, NullableType(TypeRef("Date")), false, None),
      ),
      interfaces = List(TypeRef("Profile"))
    ),
    InterfaceType(
      name = "Profile",
      description = Some("Profile interface type"),
      fields = List(
        Field("id", None, Nil, NullableType(StringType), false, None)
      )
    ),
    ObjectType(
      name = "Date",
      description = Some("Date object type"),
      fields = List(
        Field("day", None, Nil, NullableType(IntType), false, None),
        Field("month", None, Nil, NullableType(IntType), false, None),
        Field("year", None, Nil, NullableType(IntType), false, None)
      ),
      interfaces = Nil
    ),
    UnionType(
      name = "Choice",
      description = Some("Choice union type"),
      members = List(TypeRef("User"), TypeRef("Date"))
    ),
    EnumType(
      name = "Flags",
      description = Some("Flags enum type"),
      enumValues = List(
        EnumValue("A", None),
        EnumValue("B", None, true, Some("Deprecation test")),
        EnumValue("C", None)
      )
    ),
    ObjectType(
      name = "KindTest",
      description = None,
      fields = List(
        Field("scalar", Some("Scalar field"), Nil, NullableType(IntType), false, None),
        Field("object", Some("Object field"), Nil, NullableType(TypeRef("User")), false, None),
        Field("interface", Some("Interface field"), Nil, NullableType(TypeRef("Profile")), false, None),
        Field("union", Some("Union field"), Nil, NullableType(TypeRef("Choice")), false, None),
        Field("enum", Some("Enum field"), Nil, NullableType(TypeRef("Flags")), false, None),
        Field("list", Some("List field"), Nil, NullableType(ListType(NullableType(IntType))), false, None),
        Field("nonnull", Some("Nonnull field"), Nil, IntType, false, None),
        Field("nonnulllistnonnull", Some("Nonnull list of nonnull field"), Nil, ListType(IntType), false, None)
      ),
      interfaces = Nil
    ),
    ObjectType(
      name = "DeprecationTest",
      description = None,
      fields = List(
        Field("user", None, Nil, NullableType(TypeRef("User")), false, None),
        Field("flags", None, Nil, NullableType(TypeRef("Flags")), false, None)
      ),
      interfaces = Nil
    )
  )

  val directives = Nil
}

object SmallSchema extends Schema {
  import ScalarType._

  val types = List(
    ObjectType(
      name = "Query",
      description = None,
      fields = List(
        Field("users", None, Nil, ListType(TypeRef("User")), false, None)
      ),
      interfaces = Nil
    ),
    ObjectType(
      name = "User",
      description = Some("User object type"),
      fields = List(
        Field("id", None, Nil, NullableType(StringType), false, None),
        Field("name", None, Nil, NullableType(StringType), false, None),
        Field("age", None, Nil, NullableType(IntType), false, None)
      ),
      interfaces = Nil
    )
  )

  val directives = Nil
}
