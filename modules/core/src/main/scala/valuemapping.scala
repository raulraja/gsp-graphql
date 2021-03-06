// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle

import scala.reflect.ClassTag

import cats.Monad
import cats.implicits._
import io.circe.Json

import QueryInterpreter.{mkErrorResult, mkOneError}
import ScalarType._

trait ValueMapping[F[_]] extends AbstractMapping[Monad, F] {

  case class ValueRoot(val tpe: Type, val fieldName: String, root0: () => Any) extends RootMapping {
    lazy val root: Any = root0()
    def cursor(query: Query): F[Result[Cursor]] = {
      val fieldTpe = tpe.field(fieldName)
      val cursorTpe = query match {
        case _: Query.Unique => fieldTpe.nonNull.list
        case _ => fieldTpe
      }
      ValueCursor(cursorTpe, root, Nil).rightIor.pure[F].widen
    }
    def withParent(tpe: Type): ValueRoot =
      new ValueRoot(tpe, fieldName, root0)
  }

  object ValueRoot {
    def apply(fieldName: String, root: => Any): ValueRoot =
      new ValueRoot(NoType, fieldName, () => root)
  }

  sealed trait ValueField0[T] extends FieldMapping
  object ValueField0 {
    implicit def wrap[T](fm: FieldMapping): ValueField0[T] = Wrap(fm)
    case class Wrap[T](fm: FieldMapping) extends ValueField0[T] {
      def fieldName = fm.fieldName
      def withParent(tpe: Type): FieldMapping = fm.withParent(tpe)
    }
  }
  case class ValueField[T](fieldName: String, f: T => Any) extends ValueField0[T] {
    def withParent(tpe: Type): ValueField[T] = this
  }
  case class ValueAttribute[T](fieldName: String, f: T => Any) extends ValueField0[T] {
    def withParent(tpe: Type): ValueAttribute[T] = this
  }

  case class ValueObjectMapping[T](
    tpe: Type,
    fieldMappings: List[FieldMapping],
    classTag: ClassTag[T]
  ) extends ObjectMapping

  def ValueObjectMapping[T: ClassTag](
    tpe: Type,
    fieldMappings: List[ValueField0[T]]
  )(implicit classTag: ClassTag[T]): ValueObjectMapping[T] =
    new ValueObjectMapping(tpe, fieldMappings.map(_.withParent(tpe)), classTag)

  case class ValueCursor(
    tpe:   Type,
    focus: Any,
    path:  List[String]
  ) extends Cursor {
    def isLeaf: Boolean =
      tpe.dealias match {
        case (_: ScalarType)|(_: EnumType) => true
        case _ => leafMapping(tpe).isDefined
      }

    def asLeaf: Result[Json] =
      leafMapping(tpe) match {
        case Some(mapping) => mapping.asInstanceOf[LeafMapping[Any]].encoder(focus).rightIor
        case None =>
          (tpe.dealias, focus) match {
            case (StringType,  s: String)  => Json.fromString(s).rightIor
            case (IDType,      s: String)  => Json.fromString(s).rightIor
            case (IntType,     i: Int)     => Json.fromInt(i).rightIor
            case (IntType,     l: Long)    => Json.fromLong(l).rightIor
            case (FloatType,   f: Float)   => Json.fromFloat(f).toRightIor(mkOneError(s"Unrepresentable float %d"))
            case (FloatType,   d: Double)  => Json.fromDouble(d).toRightIor(mkOneError(s"Unrepresentable double %d"))
            case (BooleanType, b: Boolean) => Json.fromBoolean(b).rightIor
            case (_: EnumType, e: Enumeration#Value) => Json.fromString(e.toString).rightIor
            case _ =>
              mkErrorResult(s"Expected Scalar type, found $tpe for focus ${focus}")
          }
      }

    def isList: Boolean = (tpe, focus) match {
      case (_: ListType, _: List[_]) => true
      case _ => false
    }

    def asList: Result[List[Cursor]] = (tpe, focus) match {
      case (ListType(tpe), it: List[_]) => it.map(f => copy(tpe = tpe, focus = f)).rightIor
      case _ => mkErrorResult(s"Expected List type, found $tpe")
    }

    def isNullable: Boolean = (tpe, focus) match {
      case (_: NullableType, _: Option[_]) => true
      case _ => false
    }

    def asNullable: Result[Option[Cursor]] = (tpe, focus) match {
      case (NullableType(tpe), o: Option[_]) => o.map(f => copy(tpe = tpe, focus = f)).rightIor
      case (_: NullableType, _) => mkErrorResult(s"Found non-nullable $focus for $tpe")
      case _ => mkErrorResult(s"Expected Nullable type, found $focus for $tpe")
    }

    def narrowsTo(subtpe: TypeRef): Boolean =
      subtpe <:< tpe &&
        objectMapping(path, subtpe).map {
          case ValueObjectMapping(_, _, classTag) =>
            classTag.runtimeClass.isInstance(focus)
          case _ => false
        }.getOrElse(false)


    def narrow(subtpe: TypeRef): Result[Cursor] =
      if (narrowsTo(subtpe))
        copy(tpe = subtpe).rightIor
      else
        mkErrorResult(s"Focus ${focus} of static type $tpe cannot be narrowed to $subtpe")

    def hasField(fieldName: String): Boolean =
      tpe.hasField(fieldName) && fieldMapping(path, tpe, fieldName).isDefined

    def field(fieldName: String): Result[Cursor] =
      fieldMapping(path, tpe, fieldName) match {
        case Some(ValueField(_, f)) =>
          copy(tpe = tpe.field(fieldName), focus = f.asInstanceOf[Any => Any](focus), path = fieldName :: path).rightIor
        case Some(CursorField(_, f, _)) =>
          f(this).map(res => copy(tpe = tpe.field(fieldName), focus = res))
        case _ =>
          mkErrorResult(s"No field '$fieldName' for type $tpe")
      }

    def hasAttribute(attrName: String): Boolean =
      !tpe.hasField(attrName) && fieldMapping(path, tpe, attrName).isDefined

    def attribute(attrName: String): Result[Any] =
      fieldMapping(path, tpe, attrName) match {
        case Some(ValueAttribute(_, f)) =>
          f.asInstanceOf[Any => Any](focus).rightIor
        case Some(CursorAttribute(_, f)) =>
          f(this)
        case _ =>
          mkErrorResult(s"No attribute '$attrName' for type $tpe")
      }
  }
}
