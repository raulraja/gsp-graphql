// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package composed

import cats.tests.CatsSuite
import io.circe.literal.JsonStringContext

final class ComposedSpec extends CatsSuite {
  test("simple currency query") {
    val query = """
      query {
        fx(code: "GBP") {
          code
          exchangeRate
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "fx": {
            "code": "GBP",
            "exchangeRate": 1.25
          }
        }
      }
    """

    val compiledQuery = CurrencyMapping.compiler.compile(query).right.get
    val res = CurrencyMapping.interpreter.run(compiledQuery, CurrencyMapping.schema.queryType)
    //println(res)

    assert(res == expected)
  }

  test("simple country query") {
    val query = """
      query {
        country(code: "GBR") {
          name
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "country": {
            "name": "United Kingdom"
          }
        }
      }
    """

    val compiledQuery = CountryMapping.compiler.compile(query).right.get
    val res = CountryMapping.interpreter.run(compiledQuery, CountryMapping.schema.queryType)
    //println(res)

    assert(res == expected)
  }

  test("simple nested query") {
    val query = """
      query {
        country(code: "GBR") {
          name
          currency {
            code
            exchangeRate
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "country" : {
            "name" : "United Kingdom",
            "currency": {
              "code": "GBP",
              "exchangeRate": 1.25
            }
          }
        }
      }
    """

    val compiledQuery = ComposedMapping.compiler.compile(query).right.get
    val res = ComposedMapping.interpreter.run(compiledQuery, ComposedMapping.schema.queryType)
    //println(res)

    assert(res == expected)
  }

  test("simple multiple nested query") {
    val query = """
      query {
        countries {
          name
          currency {
            code
            exchangeRate
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "countries" : [
            {
              "name" : "Germany",
              "currency" : {
                "code" : "EUR",
                "exchangeRate" : 1.12
              }
            },
            {
              "name" : "France",
              "currency" : {
                "code" : "EUR",
                "exchangeRate" : 1.12
              }
            },
            {
              "name" : "United Kingdom",
              "currency" : {
                "code" : "GBP",
                "exchangeRate" : 1.25
              }
            }
          ]
        }
      }
    """

    val compiledQuery = ComposedMapping.compiler.compile(query).right.get
    val res = ComposedMapping.interpreter.run(compiledQuery, ComposedMapping.schema.queryType)
    //println(res)

    assert(res == expected)
  }

  test("multiple aliased root queries") {
    val query = """
      query {
        gbr: country(code: "GBR") {
          name
          currency {
            code
          }
        }
        fra: country(code: "FRA") {
          name
          currency {
            code
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "gbr" : {
            "name" : "United Kingdom",
            "currency" : {
              "code" : "GBP"
            }
          },
          "fra" : {
            "name" : "France",
            "currency" : {
              "code" : "EUR"
            }
          }
        }
      }
    """

    val compiledQuery = ComposedMapping.compiler.compile(query).right.get
    val res = ComposedMapping.interpreter.run(compiledQuery, ComposedMapping.schema.queryType)
    //println(res)

    assert(res == expected)
  }

  test("nested aliased query") {
    val query = """
      query {
        country(code: "GBR") {
          name
          fx: currency {
            code
            exchangeRate
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "country" : {
            "name" : "United Kingdom",
            "fx": {
              "code": "GBP",
              "exchangeRate": 1.25
            }
          }
        }
      }
    """

    val compiledQuery = ComposedMapping.compiler.compile(query).right.get
    val res = ComposedMapping.interpreter.run(compiledQuery, ComposedMapping.schema.queryType)
    //println(res)

    assert(res == expected)
  }

  test("composed query with introspection") {
    val query = """
      query {
        country(code: "GBR") {
          __typename
          name
          currency {
            __typename
            code
            exchangeRate
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "country" : {
            "__typename" : "Country",
            "name" : "United Kingdom",
            "currency" : {
              "__typename" : "Currency",
              "code" : "GBP",
              "exchangeRate" : 1.25
            }
          }
        }
      }
    """

    val compiledQuery = ComposedMapping.compiler.compile(query).right.get
    val res = ComposedMapping.interpreter.run(compiledQuery, ComposedMapping.schema.queryType)
    //println(res)

    assert(res == expected)
  }
}
