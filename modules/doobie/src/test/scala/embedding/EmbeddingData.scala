// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package embedding

import cats.effect.Sync
import doobie.Transactor
import io.chrisdavenport.log4cats.Logger

import edu.gemini.grackle._, doobie._

class EmbeddingMapping[F[_]: Sync](val transactor: Transactor[F], val logger: Logger[F]) extends DoobieMapping[F] {
  val schema =
    Schema(
      """
        type Query {
          films: [Film!]!
          series: [Series!]!
        }
        type Film {
          title: String!
          synopses: Synopses!
        }
        type Series {
          title: String!
          synopses: Synopses!
        }
        type Synopses {
          short: String!
          long: String!
        }
      """
    ).right.get

  val QueryType = schema.ref("Query")
  val FilmType = schema.ref("Film")
  val SeriesType = schema.ref("Series")
  val SynopsesType = schema.ref("Synopses")

  import DoobieFieldMapping._

  val typeMappings =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings =
          List(
            DoobieRoot("films"),
            DoobieRoot("series")
          )
      ),
      ObjectMapping(
        tpe = FilmType,
        fieldMappings =
          List(
            DoobieField("title", ColumnRef("films", "title"), key = true),
            DoobieObject("synopses", Subobject(Nil))
          )
      ),
      ObjectMapping(
        tpe = SeriesType,
        fieldMappings =
          List(
            DoobieField("title", ColumnRef("series", "title"), key = true),
            DoobieObject("synopses", Subobject(Nil))
          )
      ),
      ObjectMapping(
        tpe = SynopsesType,
        path = List("films", "synopses"),
        fieldMappings =
          List(
            DoobieField("short", ColumnRef("films", "synopsis_short")),
            DoobieField("long", ColumnRef("films", "synopsis_long"))
          )
      ),
      ObjectMapping(
        tpe = SynopsesType,
        path = List("series", "synopses"),
        fieldMappings =
          List(
            DoobieField("short", ColumnRef("series", "synopsis_short")),
            DoobieField("long", ColumnRef("series", "synopsis_long"))
          )
      )
    )
}

object EmbeddingMapping {
  def fromTransactor[F[_] : Sync : Logger](transactor: Transactor[F]): EmbeddingMapping[F] =
    new EmbeddingMapping[F](transactor, Logger[F])
}

