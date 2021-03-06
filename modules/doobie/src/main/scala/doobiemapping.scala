// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle
package doobie

import java.sql.ResultSet
import org.postgresql.util.PGobject
import scala.reflect.ClassTag
import scala.util.matching.Regex

import cats.effect.Sync
import cats.implicits._
import cats.kernel.Monoid
import _root_.doobie.{ ConnectionIO, Fragment, Fragments, Read }
import _root_.doobie.implicits._
import _root_.doobie.enum.Nullability._
import _root_.doobie.postgres.implicits._
import _root_.doobie.util.Put
import _root_.doobie.util.fragment.Elem.Arg
import _root_.doobie.util.fragments
import _root_.doobie.util.meta.Meta
import _root_.doobie.util.transactor.Transactor
import io.chrisdavenport.log4cats.Logger
import io.circe.{ Encoder, Json }
import io.circe.parser._

import QueryCompiler._
import DoobiePredicate._
import Predicate._
import Query._
import QueryInterpreter.mkErrorResult
import Row.FailedJoin
import ScalarType._
import circe.AbstractCirceMapping
import cats.data.NonEmptyList
import cats.data.Chain
import edu.gemini.grackle.QueryInterpreter.ProtoJson
import cats.data.Ior
import cats.kernel.Eq

trait DoobieMapping[F[_]] extends AbstractCirceMapping[Sync, F] {
  type Table = List[Row]

  val transactor: Transactor[F]
  val logger: Logger[F]

  import DoobieFieldMapping._

  override def rootMapping(path: List[String], tpe: Type, fieldName: String): Option[RootMapping] =
    if (tpe =:= schema.queryType) super.rootMapping(path, tpe, fieldName)
    else Some(DoobieRoot(fieldName, path, tpe))

  def doobieLeafMapping[T](tpe: Type): Option[DoobieLeafMapping[T]] =
    leafMapping[T](tpe).collectFirst {
      case dlm: DoobieLeafMapping[T] => dlm.asInstanceOf[DoobieLeafMapping[T]]
    }

  def typeToMeta(tpe: Type): (Meta[_], NullabilityKnown) = {
    def simpleTypeToMeta(tpe: Type): Meta[_] =
      doobieLeafMapping[Any](tpe).map(_.meta).getOrElse(
        (tpe: @unchecked) match {
          case IntType => Meta[Int]
          case FloatType => Meta[Double]
          case StringType => Meta[String]
          case BooleanType => Meta[Boolean]
          case IDType => Meta[String]
        }
      )

    def listMeta[T: ClassTag](implicit m: Meta[Array[T]]): Meta[List[T]] =
      m.imap(_.toList)(_.toArray)

    def listTypeToMeta(tpe: Type): Meta[_] =
      doobieLeafMapping[Any](ListType(tpe)).map(_.meta).getOrElse(
        (tpe: @unchecked) match {
          case IntType => listMeta[Int]
          case FloatType => listMeta[Double]
          case StringType => listMeta[String]
          case BooleanType => listMeta[Boolean]
          case IDType => listMeta[String]
          case NullableType(IntType) => listMeta[Option[Int]]
          case NullableType(FloatType) => listMeta[Option[Double]]
          case NullableType(StringType) => listMeta[Option[String]]
          case NullableType(BooleanType) => listMeta[Option[Boolean]]
          case NullableType(IDType) => listMeta[Option[String]]
        }
      )

    tpe match {
      case ListType(tpe) => (listTypeToMeta(tpe), NoNulls)
      case NullableType(ListType(tpe)) => (listTypeToMeta(tpe), Nullable)
      case NullableType(tpe) => (simpleTypeToMeta(tpe), Nullable)
      case tpe => (simpleTypeToMeta(tpe), NoNulls)
    }
  }

  def attributeMapping(path: List[String], tpe: Type, attrName: String): Option[DoobieAttribute] =
    fieldMapping(path, tpe, attrName) match {
      case Some(am: DoobieAttribute) => Some(am)
      case _ => None
    }

  def columnsForField(path: List[String], tpe: Type, fieldName: String): List[ColumnRef] = {
    val obj = tpe.underlyingObject
    fieldMapping(path, obj, fieldName) match {
      case Some(DoobieField(_, cr, _, _)) => List(cr)
      case Some(DoobieJson(_, cr)) => List(cr)
      case Some(DoobieObject(_, Subobject(joins))) => joins.map(_.parent) ++ joins.map(_.child)
      case _ => Nil
    }
  }

  def joinsForField(path: List[String], tpe: Type, fieldName: String): List[Join] = {
    val obj = tpe.underlyingObject
    fieldMapping(path, obj, fieldName) match {
      case Some(DoobieObject(_, Subobject(joins))) => joins
      case _ => Nil
    }
  }

  def columnForAttribute(path: List[String], tpe: Type, attrName: String): Option[ColumnRef] = {
    val obj = tpe.underlyingObject
    attributeMapping(path, obj, attrName) match {
      case Some(DoobieAttribute(_, cr, _, _, _, _)) => Some(cr)
      case _ => None
    }
  }

  def primaryColumnForField(path: List[String], tpe: Type, fieldName: String): Option[ColumnRef] = {
    val obj = tpe.underlyingObject
    fieldMapping(path, obj, fieldName) match {
      case Some(DoobieField(_, cr, _, _)) => Some(cr)
      case Some(DoobieJson(_, cr)) => Some(cr)
      case _ => None
    }
  }

  def primaryColumnForTerm[T](path: List[String], tpe: Type, term: Term[T]): Option[ColumnRef] =
    term match {
      case Const(_) => None
      case termPath: Path =>
        val obj = tpe.underlyingObject
        val prefix = termPath.path.init
        val parent = obj.path(prefix)
        val name = termPath.path.last
        if (isField(termPath))
          primaryColumnForField(path.reverse_:::(termPath.path), parent, name)
        else
          columnForAttribute(path, parent, name)
    }

  def key(om: ObjectMapping): List[ColumnRef] =
    om.fieldMappings.collect {
      case cm: DoobieField if cm.key => cm.columnRef
      case am: DoobieAttribute if am.key => am.col
    }

  def keyColumnsForType(path: List[String], tpe: Type): List[ColumnRef] = {
    val obj = tpe.underlyingObject
    objectMapping(path, obj) match {
      case Some(om) => key(om)
      case _ => Nil
    }
  }

  def discriminator(om: ObjectMapping): List[ColumnRef] =
    om.fieldMappings.collect {
      case cm: DoobieField if cm.discriminator => cm.columnRef
      case am: DoobieAttribute if am.discriminator => am.col
    }

  def discriminatorColumnsForType(path: List[String], tpe: Type): List[ColumnRef] = {
    val obj = tpe.underlyingObject
    objectMapping(path, obj) match {
      case Some(om) => discriminator(om)
      case _ => Nil
    }
  }

  def hasDiscriminator(path: List[String], tpe: Type): Boolean = {
    val obj = tpe.underlyingObject
    objectMapping(path, obj) match {
      case Some(om) => discriminator(om).nonEmpty
      case _ => false
    }
  }

  // This is partial, however, we should be able to perform a consistency check ahead of time
  // such that a valid query is guaranteed to be covered.
  def mapQuery(q: Query, path: List[String], tpe: Type): MappedQuery = {
    type Acc = (List[ColumnRef], List[Join], List[(List[String], Type, Predicate)], List[(ObjectMapping, Type)])
    implicit object MAcc extends Monoid[Acc] {
      def combine(x: Acc, y: Acc): Acc =
        (x._1 ++ y._1, x._2 ++ y._2, x._3 ++ y._3, x._4 ++ y._4)

      def empty: Acc =  (Nil, Nil, Nil, Nil)
    }

    def loop(q: Query, path: List[String], tpe: Type, acc: Acc): Acc = {
      val obj = tpe.underlyingObject
      lazy val interfaces =
        obj.underlyingObject match {
          case ObjectType(_, _, _, interfaces) => interfaces
          case _ => Nil
        }

      def requiredCols: List[ColumnRef] =
        discriminatorColumnsForType(path, obj) ++ interfaces.flatMap(discriminatorColumnsForType(path, _)) ++ keyColumnsForType(path, obj)

      def requiredMappings: List[(ObjectMapping, Type)] =
        objectMapping(path, obj).map(om => (om, obj)).toList ++
        interfaces.flatMap(i => objectMapping(path, i).map(im => (im, i)).toList)

      def loopPredicate(pred: Predicate): Acc = {
        def loopPath(term: Path): Acc = {
          def mkSelects(path: List[String]): Query =
            path.foldRight(Empty: Query) { (fieldName, child) => Select(fieldName, Nil, child) }

          val prefix = term.path.init
          val parent = obj.path(prefix)
          val name = term.path.last

          if (isField(term)) {
            loop(mkSelects(term.path), path, obj, acc)
          } else {
            columnForAttribute(path, parent, name) match {
              case Some(pcol) =>
                (pcol :: requiredCols, List.empty[Join], List.empty[(List[String], Type, Predicate)], requiredMappings) |+| loop(mkSelects(prefix), path, obj, acc)
              case _ =>
                loop(mkSelects(prefix), path, obj, acc)
            }
          }
        }

        paths(pred).foldMap(loopPath) |+|
          ((List.empty[ColumnRef], List.empty[Join], List((path, tpe, pred)), List.empty[(ObjectMapping, Type)]))
      }

      q match {
        case Select(fieldName, _, child) =>
          val fieldTpe = obj.field(fieldName)
          val cols = columnsForField(path, obj, fieldName).toList ++ requiredCols
          val joins = joinsForField(path, obj, fieldName)

          loop(child, fieldName :: path, fieldTpe, (cols, joins, List.empty[(List[String], Type, Predicate)], requiredMappings) |+| acc)

        case Context(contextPath, child) =>
          loop(child, contextPath, tpe, acc)

        case Narrow(subtpe, child) =>
          loop(child, path, subtpe, acc)
        case Filter(pred, child) =>
          loop(child, path, obj, loopPredicate(pred) |+| acc)
        case Unique(pred, child) =>
          loop(child, path, obj, loopPredicate(pred) |+| acc)
        case Wrap(_, q) =>
          loop(q, path, obj, acc)
        case Rename(_, q) =>
          loop(q, path, obj, acc)
        case Group(queries) =>
          queries.foldLeft(acc) {
            case (acc, sibling) => loop(sibling, path, obj, acc)
          }
        case GroupList(queries) =>
          queries.foldLeft(acc) {
            case (acc, sibling) => loop(sibling, path, obj, acc)
          }
        case Limit(_, child) =>
          loop(child, path, obj, acc)
        case OrderBy(_, child) =>
          loop(child, path, obj, acc)

        case GroupBy(_, child) =>
          loop(child, path, obj, acc)

        case Empty | Query.Component(_, _, _) | (_: Introspect) | (_: Defer) | (_: UntypedNarrow) | (_: Skip) => acc
      }
    }

    val (columns0, joins0, predicates, mappings0) = loop(q, path, tpe, MAcc.empty)

    val columns = columns0.distinct
    val mappings = mappings0.toMap
    val joins = joins0.distinctBy(_.normalize)

    def numChildren(t: String): Int =
      joins.filter(_.parent.table == t).distinctBy(_.child.table).size

    val tables = columns.map(_.table).distinct
    val childTables = joins.map(_.child.table).toSet
    val rootTable = tables.filterNot(childTables) match {
      case List(rt) => rt
      case _ => tables.maxBy(numChildren)
    }

    val orderedJoins = {
      def orderJoins(seen: Set[String], joins: List[Join], acc: List[Join]): List[Join] = {
        if (joins.isEmpty) acc
        else {
          val (admissable, rest) = joins.partition(j => seen(j.parent.table))
          if (admissable.isEmpty) sys.error(s"unable to order joins $joins given $seen")
          val ats = admissable.map(_.child.table)
          orderJoins(seen ++ ats, rest, admissable ++ acc)
        }
      }

      orderJoins(Set(rootTable), joins, Nil).reverse
    }

    val jsonMeta: Meta[Json] =
      Meta.Advanced.other[PGobject]("json").timap[Json](
        a => parse(a.getValue).leftMap[Json](e => throw e).merge)(
        a => {
          val o = new PGobject
          o.setType("json")
          o.setValue(a.noSpaces)
          o
        }
      )

    val metas = {
      def metaForColumn(col: ColumnRef): (Boolean, (Meta[_], NullabilityKnown)) = {
        def loop(tms: List[TypeMapping]): Option[(Meta[_], NullabilityKnown)] =
          tms match {
            case Nil => None
            case tm :: tl =>
              tm match {
                case om: ObjectMapping =>
                  (om.fieldMappings.collectFirst {
                    case DoobieField(fieldName, `col`, _, _) if mappings.contains(om) =>
                      val obj = mappings(om)
                      val fieldTpe0 = obj.field(fieldName)
                      val fieldTpe =
                        if (obj.variantField(fieldName)) fieldTpe0.nullable
                        else fieldTpe0
                      typeToMeta(fieldTpe)
                    case DoobieJson(fieldName, `col`) if mappings.contains(om) =>
                      val obj = mappings(om)
                      val nullable = obj.field(fieldName).isNullable || obj.variantField(fieldName)
                      (jsonMeta, if (nullable) Nullable else NoNulls)
                    case DoobieAttribute(_, `col`, meta, _, nullable, _) =>
                      (meta, if (nullable) Nullable else NoNulls)
                  }).orElse(loop(tl))
                case pm: PrefixedMapping =>
                  loop(pm.mappings.map(_._2)).orElse(loop(tl))
                case _ =>
                  loop(tl)
              }
          }

        // A column is the product of an outer join (and may therefore be null even if it's non-nullable
        // in the schema) if its table introduced on the child side of a `Join`.
        def isJoin(cr: ColumnRef): Boolean = childTables(cr.table)

        loop(typeMappings).map(mn => (isJoin(col), mn)).getOrElse(sys.error(s"No Get for $col"))
      }

      columns.map(metaForColumn)
    }

    new MappedQuery(rootTable, columns, metas, predicates, orderedJoins)
  }

  case class DoobieRoot(fieldName: String, path: List[String] = Nil, rootTpe: Type = NoType) extends RootMapping {
    def cursor(query: Query): F[Result[Cursor]] =
      if (fieldName == "__staged") {
        val fieldTpe = rootTpe
        val mapped = mapQuery(query, path, fieldTpe)

        val cursorType = fieldTpe.list

        for {
          table <- logger.info(s"fetch(${mapped.fragment})") *> mapped.fetch.transact(transactor)
        } yield DoobieCursor(path, cursorType, table, mapped).rightIor

      } else {
        val fieldTpe = rootTpe.underlyingField(fieldName)
        val mapped = mapQuery(query, fieldName :: path, fieldTpe)

        val cursorType = fieldTpe.list

        for {
          table <- logger.info(s"fetch(${mapped.fragment})") *> mapped.fetch.transact(transactor)
        } yield DoobieCursor(fieldName :: path, cursorType, table, mapped).rightIor
      }

    def withParent(tpe: Type): DoobieRoot =
      copy(rootTpe = tpe)
  }

  trait DoobieInterfaceMapping extends ObjectMapping {
    def discriminate(cursor: Cursor): Result[Type]
  }

  object DoobieInterfaceMapping {
    abstract case class DefaultInterfaceMapping(tpe: Type, fieldMappings: List[FieldMapping], path: List[String])
      extends DoobieInterfaceMapping

    val defaultDiscriminator: Cursor => Result[Type] = (cursor: Cursor) => cursor.tpe.rightIor

    def apply(tpe: Type, fieldMappings: List[FieldMapping], path: List[String] = Nil, discriminator: Cursor => Result[Type] = defaultDiscriminator): ObjectMapping =
      new DefaultInterfaceMapping(tpe, fieldMappings.map(_.withParent(tpe)), path) {
        def discriminate(cursor: Cursor): Result[Type] = discriminator(cursor)
      }
  }

  case class DoobieAttribute(
    fieldName: String,
    col: ColumnRef,
    meta: Meta[_],
    key: Boolean,
    nullable: Boolean,
    discriminator: Boolean
  ) extends FieldMapping {
    def withParent(tpe: Type): FieldMapping = this
  }

  def DoobieAttribute[T](
    fieldName: String,
    col: ColumnRef,
    key: Boolean = false,
    nullable: Boolean = false,
    discriminator: Boolean = false
  )(implicit meta: Meta[T]): DoobieAttribute =
    new DoobieAttribute(fieldName, col, meta, key, nullable, discriminator)

  sealed trait DoobieFieldMapping extends FieldMapping {
    def withParent(tpe: Type): FieldMapping = this
  }

  object DoobieFieldMapping {
    case class DoobieField(
      fieldName: String,
      columnRef: ColumnRef,
      key: Boolean = false,
      discriminator: Boolean = false
    ) extends DoobieFieldMapping
    case class DoobieObject(fieldName: String, subobject: Subobject) extends DoobieFieldMapping
    case class DoobieJson(fieldName: String, columnRef: ColumnRef) extends DoobieFieldMapping
  }

  case class DoobieLeafMapping[T](val tpe: Type, val encoder: Encoder[T], val meta: Meta[T]) extends LeafMapping[T]
  object DoobieLeafMapping {
    def apply[T](tpe: Type)(implicit encoder: Encoder[T], meta: Meta[T], dummy: DummyImplicit) =
      new DoobieLeafMapping(tpe, encoder, meta)
  }

  case class ColumnRef(table: String, column: String) {
    def toSql: String = s"$table.$column"
  }

  case class Subobject(joins: List[Join])

  case class Join(parent: ColumnRef, child: ColumnRef) {
    def normalize: Join = {
      if (parent.table > child.table) this
      else if (parent.table == child.table && parent.column >= child.column) this
      else Join(child, parent)
    }
    def swap: Join = Join(child, parent)

    def toSql: String = s"LEFT JOIN ${child.table} ON ${parent.toSql} = ${child.toSql}"
  }

  case class MappedQuery(
    table: String,
    columns: List[ColumnRef],
    metas: List[(Boolean, (Meta[_], NullabilityKnown))],
    predicates: List[(List[String], Type, Predicate)],
    joins: List[Join]
  ) {
    override def toString: String = {
      columns.toString
    }

    def index(col: ColumnRef): Int =
      columns.indexOf(col)

    def project(row: Row, cols: List[ColumnRef]): Row =
      Row(cols.map(cr => row(index(cr))))

    def select(row: Row, col: ColumnRef): Any =
      row(index(col))

    def selectField(row: Row, path: List[String], tpe: Type, fieldName: String): Result[Any] = {
      val obj = tpe.dealias
      fieldMapping(path, obj, fieldName) match {
        case Some(DoobieField(_, col: ColumnRef, _, _)) => select(row, col).rightIor
        case Some(DoobieJson(_, col: ColumnRef)) => select(row, col).rightIor
        case other => mkErrorResult(s"Expected mapping for field $fieldName of type $obj, found $other")
      }
    }

    def hasAttribute(path: List[String], tpe: Type, attrName: String): Boolean = {
      val obj = tpe.dealias
      attributeMapping(path, obj, attrName).map(_ => true).getOrElse(false)
    }

    def selectAttribute(row: Row, path: List[String], tpe: Type, attrName: String): Result[Any] = {
      val obj = tpe.dealias
      attributeMapping(path, obj, attrName).map(_.col) match {
        case Some(col: ColumnRef) => select(row, col).rightIor
        case other => mkErrorResult(s"Expected mapping for attribute $attrName of type $obj, found $other")
      }
    }

    def stripNulls(table: Table, path: List[String], tpe: Type): Table =
      objectMapping(path, tpe.nonNull) match {
        case Some(om) if key(om).nonEmpty =>
          val cols = key(om)
          table.filterNot(row => project(row, cols).elems.exists(_ == FailedJoin))
        case _ => table
      }

    def narrowsTo(table: Table, path: List[String], tpe: Type): Boolean =
      objectMapping(path, tpe.nonNull) match {
        case Some(om) if key(om).nonEmpty =>
          val cols = key(om)
          !table.exists(row => project(row, cols).elems.exists(_ == FailedJoin))
        case _ => false
      }

    def group(table: Table, path: List[String], tpe: Type): List[Table] =
      objectMapping(path, tpe) match {
        case Some(om) if key(om).nonEmpty =>
          val cols = key(om)
          val nonNull = table.filterNot(row => project(row, cols).elems.exists(_ == FailedJoin))
          nonNull.groupBy(row => project(row, cols)).to(List).sortBy(_._1.toString).map(_._2)
        case _ => table.map(List(_))
      }

    def fetch: ConnectionIO[Table] =
      fragment.query[Row](Row.mkRead(metas)).to[List]

    def fragmentForPred(path: List[String], tpe: Type, pred: Predicate): Option[Fragment] = {
      def term[T](x: Term[T], put: Put[T]): Option[Fragment] =
        x match {
          case Const(value) => Some(Fragment("?", List(Arg[T](value, put)), None))
          case other =>
            primaryColumnForTerm(path, tpe, other).map(col => Fragment.const(s"${col.toSql}"))
        }

      def putForPath[T](p: List[String]): Option[Put[T]] =
        (p match {
          case init :+ last =>
            val parentTpe = tpe.path(init).underlyingObject
            if (parentTpe.hasField(last)) {
              val fieldTpe = parentTpe.field(last).nonNull
              doobieLeafMapping[T](fieldTpe).map(_.meta.put).orElse(
                fieldTpe match {
                  case StringType => Some(Put[String])
                  case IntType => Some(Put[Int])
                  case FloatType => Some(Put[Double])
                  case BooleanType => Some(Put[Boolean])
                  case _ => None
                }
              )
            } else if (hasAttribute(p, parentTpe, last))
              attributeMapping(p, parentTpe, last).map(_.meta.put)
            else None
          case Nil => doobieLeafMapping[T](tpe.nonNull).map(_.meta.put)
        }).map(_.asInstanceOf[Put[T]])

      def putForTerm(x: Term[_]): Option[Put[_]] =
        x match {
          case path: Path => putForPath(path.path)
          case (_: And)|(_: Or)|(_: Not)|(_: Eql[_])|(_: NEql[_])|(_: Lt[_])|(_: LtEql[_])|(_: Gt[_])|(_: GtEql[_])  => Some(Put[Boolean])
          case (_: AndB)|(_: OrB)|(_: XorB)|(_: NotB) => Some(Put[Int])
          case (_: ToUpperCase)|(_: ToLowerCase) => Some(Put[String])
          case _ => None
        }

      def loop[T](exp: Term[T], put: Option[Put[T]]): Option[Fragment] = {

        def unify(x: Term[_], y: Term[_]): Option[Put[Any]] =
          putForTerm(x).orElse(putForTerm(y)).orElse(put).asInstanceOf[Option[Put[Any]]]

        exp match {
          case Const(value) =>
            put.map(pa => Fragment("?", List(Arg(value, pa)), None))

          case pathTerm: Path =>
            primaryColumnForTerm(path, tpe, pathTerm).map(col => Fragment.const(s"${col.toSql}"))

          case And(x, y) =>
            Some(Fragments.andOpt(loop(x, None), loop(y, None)))
          case Or(x, y) =>
            Some(Fragments.orOpt(loop(x, None), loop(y, None)))
          case Not(x) =>
            loop(x, Some(Put[Boolean])).map(x => fr"NOT" ++ x)

          case Eql(x, y) =>
            val p = unify(x, y)
            for {
              x <- loop(x, p)
              y <- loop(y, p)
            } yield x ++ fr0" = "++ y
          case NEql(x, y) =>
            val p = unify(x, y)
            for {
              x <- loop(x, p)
              y <- loop(y, p)
            } yield x ++ fr0" != "++ y

          case Contains(x, y) =>
            val p = unify(x, y)
            for {
              x <- loop(x, None)
              y <- loop(y, p)
            } yield x ++ fr0" = "++ y

          case Lt(x, y) =>
            val p = unify(x, y)
            for {
              x <- loop(x, p)
              y <- loop(y, p)
            } yield x ++ fr0" < "++ y
          case LtEql(x, y) =>
            val p = unify(x, y)
            for {
              x <- loop(x, p)
              y <- loop(y, p)
            } yield x ++ fr0" <= "++ y
          case Gt(x, y) =>
            val p = unify(x, y)
            for {
              x <- loop(x, p)
              y <- loop(y, p)
            } yield x ++ fr0" > "++ y
          case GtEql(x, y) =>
            val p = unify(x, y)
            for {
              x <- loop(x, p)
              y <- loop(y, p)
            } yield x ++ fr0" >= "++ y

          case In(x, y) =>
            for {
              p0 <- putForTerm(x)
              p = p0.asInstanceOf[Put[Any]]
              x <- term(x, p)
              l <- NonEmptyList.fromList(y)
            } yield fragments.in(x, l)(implicitly, p)

          case AndB(x, y) =>
            for {
              x <- term(x, Put[Int])
              y <- term(y, Put[Int])
            } yield x ++ fr0" & "++ y
          case OrB(x, y) =>
            for {
              x <- term(x, Put[Int])
              y <- term(y, Put[Int])
            } yield x ++ fr0" | "++ y
          case XorB(x, y) =>
            for {
              x <- term(x, Put[Int])
              y <- term(y, Put[Int])
            } yield x ++ fr0" # "++ y
          case NotB(x) =>
            loop(x, Some(Put[Int])).map(x => fr"~" ++ x)

          case StartsWith(x, prefix) =>
            for {
              x <- term(x, Put[String])
            } yield x ++ Fragment.const(s" LIKE ") ++ fr0"${prefix+"%"}"
          case ToUpperCase(x) =>
            loop(x, Some(Put[String])).map(x => fr"upper(" ++ x ++ fr")")
          case ToLowerCase(x) =>
            loop(x, Some(Put[String])).map(x => fr"lower(" ++ x ++ fr")")
          case Like(x, pattern, caseInsensitive) =>
            val op = if(caseInsensitive) "ILIKE" else "LIKE"
            term(x, Put[String]).map(x => x ++ Fragment.const(s" $op ") ++ fr0"$pattern")

          case _ =>
            None
        }
      }

      loop(pred, None)
    }

    lazy val fragment: Fragment = {
      val cols = columns.map(_.toSql)

      val preds = predicates.map((fragmentForPred _).tupled)
      val where = Fragments.whereAndOpt(preds: _*)

      val select =
        Fragment.const0(
          s"""
          |SELECT ${cols.mkString(", ")}
          |FROM $table${if (joins.isEmpty) "" else joins.map(_.toSql).mkString("\n", "\n", "")}
          |""".stripMargin
        )

      (select ++ where)
    }
  }

  class StagingElaborator extends Phase {
    val stagingJoin = (c: Cursor, q: Query) =>
      q match {
        case Select(_, _, _) =>
          val obj = c.tpe.underlyingObject
          val Some(om) = objectMapping(c.path, obj)

          val joinPreds =
            Predicate.and(
              om.fieldMappings.collect {
                case cm: DoobieField if cm.key =>
                  val fv0 = c.field(cm.fieldName)
                  val Ior.Right(fv) = fv0
                  Eql(FieldPath(List(cm.fieldName)), Const(fv.focus))(Eq.fromUniversalEquals)

                case am: DoobieAttribute if am.key =>
                  val av0 = c.attribute(am.fieldName)
                  val Ior.Right(av) = av0
                  Eql(AttrPath(List(am.fieldName)), Const(av))(Eq.fromUniversalEquals)
              }
            )
          Context(c.path, Select("__staged", Nil, Filter(joinPreds, q))).rightIor

        case _ => mkErrorResult(s"No staging join for non-Select $q")
      }

    override def transform(query: Query, env: Env, schema: Schema, tpe: Type): Result[Query] = {
      def nonLeafList(path: List[String], tpe: Type, fieldName: String): Boolean = {
        val fieldTpe = tpe.underlyingField(fieldName).nonNull
        fieldTpe.isList &&
          (fieldMapping(path, tpe.underlyingObject, fieldName) match {
            case Some(DoobieObject(_, Subobject(joins))) if joins.nonEmpty => true
            case _ => false
          })
      }

      case class Seen[T](context: T, seenTypes: Set[ObjectMapping], seenList: Boolean) {
        def map[U](f: T => U): Seen[U] = copy(context = f(context))
        def withQuery(q: Query): Seen[Query] = copy(context = q)
        def withType(q: Query, path: List[String], tpe: Type): Seen[Query] =
          objectMapping(path, tpe.underlyingObject).map(om =>
            copy(context = q, seenTypes = seenTypes + om)
          ).getOrElse(copy(context = q))
        def hasSeen(path: List[String], tpe: Type): Boolean = {
          objectMapping(path, tpe.underlyingObject).map(seenTypes).getOrElse(false)
        }
        def withList(q: Query, seen: => Boolean) = copy(context = q, seenList = seenList || seen)
        def forGroup: Seen[List[Query]] = copy(context = List.empty)
      }

      object Seen {
        def apply(q: Query): Seen[Query] = new Seen(q, Set.empty, false)
      }

      def loop(path: List[String], tpe: Type, seen: Seen[Query]): Result[Seen[Query]] = {
        seen.context match {
          case s@Select(fieldName, _, child) =>
            tpe.withUnderlyingField(fieldName) { childTpe =>
              if(seen.hasSeen(fieldName :: path, childTpe.underlyingObject) || (seen.seenList && nonLeafList(path, tpe, fieldName))) {
                val elaboratedSelect = loop(fieldName :: path, childTpe, Seen(child)).map(ec => s.copy(child = ec.context))
                elaboratedSelect.map(ec => seen.withQuery(Wrap(fieldName, Defer(stagingJoin, ec, tpe.underlyingObject))))
              } else if(childTpe.dealias.isInterface && childTpe.variantField(fieldName) && !hasDiscriminator(path, childTpe)) {
                val elaboratedSelect = loop(fieldName :: path, childTpe, Seen(child)).map(ec => s.copy(child = ec.context))
                elaboratedSelect.map(ec => seen.withQuery(Wrap(fieldName, Defer(stagingJoin, ec, schema.queryType))))
              } else {
                val elaboratedSelect = loop(fieldName :: path, childTpe, seen.withType(child, fieldName :: path, tpe))
                elaboratedSelect.map(ec => ec.withList(s.copy(child = ec.context), nonLeafList(path, tpe, fieldName)))
              }
            }

          case c@Context(cPath, child)   => loop(cPath, tpe, seen.withQuery(child)).map(_.map(q => c.copy(child = q)))

          case n@Narrow(subtpe, child) => loop(path, subtpe, seen.withQuery(child)).map(_.map(q => n.copy(child = q)))
          case w@Wrap(_, child)        => loop(path, tpe, seen.withQuery(child)).map(_.map(q => w.copy(child = q)))
          case r@Rename(_, child)      => loop(path, tpe, seen.withQuery(child)).map(_.map(q => r.copy(child = q)))

          case g@Group(queries)        =>
            queries.foldM(seen.forGroup) {
              case (acc, q) => loop(path, tpe, acc.withQuery(q)).map(_.map(q => q :: acc.context))
            }.map(_.map(qs => g.copy(queries = qs.reverse)))

          case g@GroupList(queries)    =>
            queries.foldM(seen.forGroup) {
              case (acc, q) => loop(path, tpe, acc.withQuery(q)).map(_.map(q => q :: acc.context))
            }.map(_.map(qs => g.copy(queries = qs.reverse)))

          case u@Unique(_, child)      =>
            loop(path, tpe.nonNull, seen.withType(child, path, tpe)).map(_.map(q => u.copy(child = q)))

          case f@Filter(_, child)      =>
            loop(path, tpe.item, seen.withType(child, path, tpe)).map(_.map(q => f.copy(child = q)))

          case l@Limit(_, child)      =>
            loop(path, tpe.item, seen.withType(child, path, tpe)).map(_.map(q => l.copy(child = q)))

          case o@OrderBy(_, child)      =>
            loop(path, tpe.item, seen.withType(child, path, tpe)).map(_.map(q => o.copy(child = q)))

          case g@GroupBy(_, child)      =>
            loop(path, tpe.item, seen.withType(child, path, tpe)).map(_.map(q => g.copy(child = q)))

          case c@Query.Component(_, _, _) => seen.withQuery(c).rightIor
          case i: Introspect           => seen.withQuery(i).rightIor
          case d: Defer                => seen.withQuery(d).rightIor
          case Empty                   => seen.withQuery(Empty).rightIor

          case s: Skip                 => mkErrorResult(s"Unexpected Skip ${s.render}")
          case n: UntypedNarrow        => mkErrorResult(s"Unexpected UntypeNarrow ${n.render}")
        }
      }

      query match {
        case g@Group(queries) =>
          queries.traverse(q => loop(Nil, tpe, Seen(q))).map(eqs => g.copy(queries = eqs.map(_.context)))
        case g@GroupList(queries) =>
          queries.traverse(q => loop(Nil, tpe, Seen(q))).map(eqs => g.copy(queries = eqs.map(_.context)))
        case other =>
          loop(Nil, tpe, Seen(other)).map(_.context)
      }
    }
  }

  override def compilerPhases: List[QueryCompiler.Phase] = (new StagingElaborator) :: super.compilerPhases

  override val interpreter: QueryInterpreter[F] = new QueryInterpreter(this) {
    override def runRootValues(queries: List[(Query, Type)]): F[(Chain[Json], List[ProtoJson])] = {
      if (queries.length == 1)
        super.runRootValues(queries)
      else {
        def isGroupable(q: Query): Boolean =
          q match {
            case Context(_, Select(_, Nil, Filter(Eql(_, Const(_)), _))) => true
            case _ => false
          }

        def groupKey(q: (Query, Type)): (List[String], String, Term[Any], Query, Type) = {
          val (Context(cpath, Select(fieldName, Nil, Filter(Eql(path, Const(_)), child))), tpe) = q
          (cpath, fieldName, path, child, tpe)
        }

        def groupConst(q: Query): Eql[Any] = {
          val Context(_, Select(_, Nil, Filter(eql: Eql[Any] @unchecked, _))) = q
          eql
        }

        val deduped = queries.zipWithIndex.groupMap(_._1)(_._2)

        val (groupable, ungroupable) =
          deduped.partition(e => isGroupable(e._1._1))

        val grouped = groupable.groupMap {
          case (qt, _) => groupKey(qt)
        }{
          case ((q, _), is) => (groupConst(q), is)
        }

        val coalesced = grouped.map {
          case ((cpath, fieldName, _, child, tpe), cis) =>
            val ncis = (cis.map { case (q, is) => (q, is.sorted) }).toList.sortBy(_._2.head)
            val (eqls, is) = ncis.unzip
            val Some(in) = In.fromEqls[Any](eqls)
            ((Context(cpath, GroupBy(in.mkDiscriminator, Select(fieldName, Nil, Filter(in, child)))), ListType(tpe)), is)
        }

        val ungroupableResults = {
          val (qts, is) = ungroupable.toList.unzip
          super.runRootValues(qts).map {
            case (errs, js) => (errs, js.zip(is))
          }
        }

        val coalescedResults = {
          val (qts, is) = coalesced.toList.unzip
          super.runRootValues(qts).map {
            case (errs, js) =>
              val unpacked = js.zip(is) flatMap { case (j, is) =>
                ProtoJson.unpackList(j).getOrElse(is.map(_ => ProtoJson.fromJson(Json.Null))).zip(is)
              }

              (errs, unpacked)
          }
        }

        for {
          eurs <- ungroupableResults
          ecrs <- coalescedResults
        } yield {
          val (errs0, urs) = eurs
          val (errs1, crs) = ecrs
          val aligned = (urs.toList ++ crs.toList) flatMap { case (j, is) => is.map(i => (i, j)) }
          (errs0 ++ errs1, aligned.sortBy(_._1).map(_._2))
        }
      }
    }
  }

  case class DoobieCursor(val path: List[String], val tpe: Type, val focus: Any, mapped: MappedQuery) extends Cursor {
    def asTable: Result[Table] = focus match {
      case table@((_: Row) :: _ | Nil) => table.asInstanceOf[Table].rightIor
      case _ => mkErrorResult(s"Not a table")
    }

    def isUnstructured(tpe: Type): Boolean =
      tpe match {
        case NullableType(tpe) => isUnstructured(tpe)
        case ListType(tpe) => isUnstructured(tpe)
        case TypeRef(_, _) => tpe.dealias.isLeaf
        case _: ScalarType => true
        case _: EnumType => true
        case _ => false
      }

    def isLeaf: Boolean = false
    def asLeaf: Result[Json] =
      mkErrorResult(s"Not a leaf: $tpe")

    def isList: Boolean =
      tpe.isList

    def asList: Result[List[Cursor]] =
      if (!tpe.isList) mkErrorResult(s"Not a list: $tpe")
      else {
        val itemTpe = tpe.item.dealias
        asTable.map { table =>

          // The object mapping for `tpe`.
          val objectMapping0: ObjectMapping =
            objectMapping(path, itemTpe).getOrElse(sys.error(s"No ObjectMapping for $itemTpe"))

          // If this mapping is a list of child objects then its fields came from an outer join. If
          // there are no children then all keys defined in the mapping will have the `FailedJoin`
          // value.
          val isEmpty: Boolean =
            key(objectMapping0).forall { cr =>
              val ix = mapped.index(cr)
              table.forall(r => r(ix) == Row.FailedJoin)
            }

          // Sanity check: isEmpty implies that we had zero rows, or one row with failed joins.
          if (isEmpty)
            assert(table.length <= 1)

          // Done!
          if (isEmpty) Nil
          else mapped.group(table, path, itemTpe).map(table => copy(tpe = itemTpe, focus = table))
        }
      }

    def isNullable: Boolean =
      tpe.isNullable

    def asNullable: Result[Option[Cursor]] =
      (tpe, focus) match {
        case (NullableType(_), Nil) => None.rightIor
        case (NullableType(tpe), _) => Some(copy(tpe = tpe)).rightIor // non-nullable column as nullable schema type (ok)
        case _ => mkErrorResult("Not nullable")
      }

    def narrowsTo(subtpe: TypeRef): Boolean = {
      val ctpe =
        objectMapping(path, tpe) match {
          case Some(im: DoobieInterfaceMapping) =>
            im.discriminate(this).getOrElse(tpe)
          case _ => tpe
        }
      if (ctpe =:= tpe)
        asTable.map(table => mapped.narrowsTo(table, path, subtpe)).right.getOrElse(false)
      else ctpe <:< subtpe
    }

    def narrow(subtpe: TypeRef): Result[Cursor] =
      if (narrowsTo(subtpe)) copy(tpe = subtpe).rightIor
      else mkErrorResult(s"Cannot narrow $tpe to $subtpe")

    def hasField(fieldName: String): Boolean =
      tpe.hasField(fieldName) && fieldMapping(path, tpe, fieldName).isDefined

    def field(fieldName: String): Result[Cursor] = {
      val fieldTpe = tpe.field(fieldName)
      fieldMapping(path, tpe.underlyingObject, fieldName) match {
        case Some(CursorField(_, f, _)) =>
          f(this).map(res => LeafCursor(tpe = fieldTpe, focus = res, fieldName :: path))

        case Some(DoobieJson(_, _)) =>
          asTable.flatMap { table =>
            mapped.selectField(table.head, path, tpe, fieldName).flatMap(_ match {
              case Some(j: Json) if fieldTpe.isNullable =>
                CirceCursor(tpe = fieldTpe, focus = j, fieldName :: path).rightIor
              case None =>
                CirceCursor(tpe = fieldTpe, focus = Json.Null, fieldName :: path).rightIor
              case j: Json if !fieldTpe.isNullable =>
                CirceCursor(tpe = fieldTpe, focus = j, fieldName :: path).rightIor
              case other =>
                mkErrorResult(s"Expected jsonb value found $other")
            })
          }

        case _ =>
          if (isUnstructured(fieldTpe))
            asTable.flatMap(table =>
              mapped.selectField(table.head, path, tpe, fieldName).map { leaf =>
                val leafFocus = leaf match {
                  case Some(f) if tpe.variantField(fieldName) && !fieldTpe.isNullable => f
                  case other => other
                }
                LeafCursor(tpe = fieldTpe, focus = leafFocus, fieldName :: path)
              }
            )
          else {
            val fieldTpe = tpe.field(fieldName)
            asTable.map(table => copy(path = fieldName :: path, tpe = fieldTpe, focus = mapped.stripNulls(table, path, fieldTpe)))
          }
      }
    }

    def hasAttribute(attributeName: String): Boolean =
      fieldMapping(path, tpe, attributeName) match {
        case Some(CursorAttribute(_, _)) => true
        case _ => mapped.hasAttribute(path, tpe, attributeName)
      }

    def attribute(attributeName: String): Result[Any] =
      fieldMapping(path, tpe, attributeName) match {
        case Some(CursorAttribute(_, f)) => f(this)
        case _ =>
          asTable.flatMap(table => mapped.selectAttribute(table.head, path, tpe, attributeName))
      }
  }

  case class LeafCursor(tpe: Type, focus: Any, path: List[String]) extends Cursor {
    def isLeaf: Boolean = tpe.isLeaf

    def asLeaf: Result[Json] =
      leafMapping[Any](tpe).map(_.encoder(focus).rightIor).getOrElse(
        focus match {
          case s: String => Json.fromString(s).rightIor
          case i: Int => Json.fromInt(i).rightIor
          case d: Double => Json.fromDouble(d) match {
              case Some(j) => j.rightIor
              case None => mkErrorResult(s"Unrepresentable double %d")
            }
          case b: Boolean => Json.fromBoolean(b).rightIor

          // This means we are looking at a column with no value because it's the result of a failed
          // outer join. This is an implementation error.
          case Row.FailedJoin => sys.error("Unhandled failed join.")

          case other =>
            mkErrorResult(s"Not a leaf: $other")
        }
      )

    def isList: Boolean =
      tpe match {
        case ListType(_) => true
        case _ => false
      }

    def asList: Result[List[Cursor]] = (tpe, focus) match {
      case (ListType(tpe), it: List[_]) => it.map(f => copy(tpe = tpe, focus = f)).rightIor
      case _ => mkErrorResult(s"Expected List type, found $tpe")
    }

    def isNullable: Boolean =
      tpe match {
        case NullableType(_) => true
        case _ => false
      }

    def asNullable: Result[Option[Cursor]] =
      (tpe, focus) match {
        case (NullableType(_), None) => None.rightIor
        case (NullableType(tpe), Some(v)) => Some(copy(tpe = tpe, focus = v)).rightIor
        case _ => mkErrorResult("Not nullable")
      }

    def narrowsTo(subtpe: TypeRef): Boolean = false
    def narrow(subtpe: TypeRef): Result[Cursor] =
      mkErrorResult(s"Cannot narrow $tpe to $subtpe")

    def hasField(fieldName: String): Boolean = false
    def field(fieldName: String): Result[Cursor] =
      mkErrorResult(s"Cannot select field '$fieldName' from leaf type $tpe")

    def hasAttribute(attributeName: String): Boolean = false
    def attribute(attributeName: String): Result[Any] =
      mkErrorResult(s"Cannot read attribute '$attributeName' from leaf type $tpe")
  }
}

object DoobiePredicate {
  def paths(t: Term[_]): List[Path] =
    t match {
      case p: Path => List(p)
      case And(x, y) => paths(x) ++ paths(y)
      case Or(x, y) => paths(x) ++ paths(y)
      case Not(x) => paths(x)
      case Eql(x, y) => paths(x) ++ paths(y)
      case NEql(x, y) => paths(x) ++ paths(y)
      case Contains(x, y) => paths(x) ++ paths(y)
      case Lt(x, y) => paths(x) ++ paths(y)
      case LtEql(x, y) => paths(x) ++ paths(y)
      case Gt(x, y) => paths(x) ++ paths(y)
      case GtEql(x, y) => paths(x) ++ paths(y)
      case In(x, _) => paths(x)
      case AndB(x, y) => paths(x) ++ paths(y)
      case OrB(x, y) => paths(x) ++ paths(y)
      case XorB(x, y) => paths(x) ++ paths(y)
      case NotB(x) => paths(x)
      case StartsWith(x, _) => paths(x)
      case ToUpperCase(x) => paths(x)
      case ToLowerCase(x) => paths(x)
      case Matches(x, _) => paths(x)
      case Like(x, _, _) => paths(x)
      case _ => Nil
    }

  def isField(p: Path): Boolean =
    p match {
      case FieldPath(_) | CollectFieldPath(_) => true
      case _ => false
    }

  def likeToRegex(pattern: String, caseInsensitive: Boolean): Regex = {
    val csr = ("^"+pattern.replace("%", ".*").replace("_", ".")+"$")
    (if (caseInsensitive) s"(?i:$csr)" else csr).r
  }

  case class Like(x: Term[String], pattern: String, caseInsensitive: Boolean) extends Predicate {
    lazy val r = likeToRegex(pattern, caseInsensitive)

    def apply(c: Cursor): Result[Boolean] = x(c).map(r.matches(_))
  }
}

case class Row(elems: List[Any]) {
  def apply(i: Int): Any = elems(i)
}

object Row {
  // Placeholder for nulls read from non-nullable columns introduced via an outer join.
  case object FailedJoin

  def mkRead(metas: List[(Boolean, (Meta[_], NullabilityKnown))]): Read[Row] = {
    def unsafeGet(rs: ResultSet, n: Int): Row =
      Row {
        metas.zipWithIndex.map {
          case ((isJoin, (m, NoNulls)),  i) =>
            if (isJoin) m.get.unsafeGetNullable(rs, n+i).getOrElse(FailedJoin)
            else m.get.unsafeGetNonNullable(rs, n+i)
          case ((_, (m, Nullable)), i) => m.get.unsafeGetNullable(rs, n+i)
        }
      }

    new Read(metas.map { case (_, (m, n)) => (m.get, n) }, unsafeGet)
  }
}
