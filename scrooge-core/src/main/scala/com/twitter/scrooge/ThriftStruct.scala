package com.twitter.scrooge

import com.twitter.scrooge.validation.Issue
import org.apache.thrift.protocol.{TProtocol, TType}
import scala.reflect.ClassTag

object ThriftStruct {
  def ttypeToString(byte: Byte): String = {
    // from https://github.com/apache/thrift/blob/master/lib/java/src/org/apache/thrift/protocol/TType.java
    byte match {
      case TType.STOP => "STOP"
      case TType.VOID => "VOID"
      case TType.BOOL => "BOOL"
      case TType.BYTE => "BYTE"
      case TType.DOUBLE => "DOUBLE"
      case TType.I16 => "I16"
      case TType.I32 => "I32"
      case TType.I64 => "I64"
      case TType.STRING => "STRING"
      case TType.STRUCT => "STRUCT"
      case TType.MAP => "MAP"
      case TType.SET => "SET"
      case TType.LIST => "LIST"
      case TType.ENUM => "ENUM"
      case _ => "UNKNOWN"
    }
  }
}

trait ThriftStruct {
  @throws(classOf[org.apache.thrift.TException])
  def write(oprot: TProtocol): Unit
}

trait HasThriftStructCodec3[T <: ThriftStruct] {
  def _codec: ThriftStructCodec3[T]
}

trait ThriftResponse[Result] {
  def successField: Option[Result]
  def exceptionFields: Iterable[Option[ThriftException]]

  /**
   * Return the first nonempty exception field.
   */
  def firstException(): Option[ThriftException] =
    exceptionFields.collectFirst(ThriftResponse.exceptionIsDefined)
}

object ThriftResponse {
  private val exceptionIsDefined: PartialFunction[Option[ThriftException], ThriftException] = {
    case Some(exception) => exception
  }
}

/**
 * Unions are tagged with this trait as well as with [[ThriftStruct]].
 */
trait ThriftUnion {

  /**
   * The type of the value contained in the union field.
   *
   * For unknown union fields, this will be `Unit`.
   *
   * @see the `ContainedType` type param on [[ThriftUnionFieldInfo]]
   */
  protected type ContainedType

  /**
   * The value of this union field.
   *
   * For unknown union fields, this will be `()`, the instance of `Unit`.
   */
  def containedValue(): ContainedType

  /**
   * The [[ThriftStructFieldInfo]] for this part of union.
   *
   * Returns `None` if this represents an unknown union field.
   */
  def unionStructFieldInfo: Option[ThriftStructFieldInfo]
}

/**
 * A trait encapsulating the logic for encoding and decoding a specific thrift struct
 * type.
 */
trait ThriftStructCodec[T <: ThriftStruct] {
  @throws(classOf[org.apache.thrift.TException])
  def encode(t: T, oprot: TProtocol): Unit

  @throws(classOf[org.apache.thrift.TException])
  def decode(iprot: TProtocol): T

  lazy val metaData: ThriftStructMetaData[T] = new ThriftStructMetaData(this)
}

abstract class ValidatingThriftStructCodec3[T <: ThriftStruct] extends ThriftStructCodec3[T] {

  /**
   * Checks that the struct is a valid as a new instance. If there are any missing required or
   * construction required fields, return a non-empty Seq of Issues.
   */
  def validateNewInstance(item: T): Seq[Issue]

  /**
   * Method that should be called on every field of a struct to validate new instances of that
   * struct. This should only called by the generated implementations of validateNewInstance.
   */
  final protected def validateField[U <: ValidatingThriftStruct[U]](any: Any): Seq[Issue] = {
    any match {
      // U is unchecked since it is eliminated by erasure, but we know that validatingStruct extends
      // from ValidatingThriftStruct. The code below should be safe for any ValidatingThriftStruct
      case validatingStruct: ValidatingThriftStruct[_] =>
        val struct: U = validatingStruct.asInstanceOf[U]
        struct._codec.validateNewInstance(struct)
      case map: collection.Map[_, _] =>
        map.flatMap {
          case (key, value) =>
            Seq(
              validateField(key),
              validateField(value)
            ).flatten
        }.toList
      case iterable: Iterable[_] => iterable.toList.flatMap(validateField)
      case option: Option[_] => option.toList.flatMap(validateField)
      case _ => Nil
    }
  }
}

/**
 * This trait extends from HasThriftStructCodec3 and ThriftStruct.
 * It should be safe to call "validatingStruct._codec.validateNewInstance(validatingStruct)"
 * on any validatingStruct that implements ValidatingThriftStruct. We take advantage of this fact in
 * the validateField method in ValidatingThriftStructCodec3.
 *
 * A method could be added to this trait that does this (with more type safety), but we want to
 * avoid adding unnecessary methods to thrift structs.
 */
trait ValidatingThriftStruct[T <: ThriftStruct] extends ThriftStruct with HasThriftStructCodec3[T] {
  self: T =>
  override def _codec: ValidatingThriftStructCodec3[T]
}

/**
 * Introduced as a backwards compatible API bridge in Scrooge 3.
 * Scala generated structs extend from this class.
 *
 * @see [[ThriftStructCodec]]
 */
abstract class ThriftStructCodec3[T <: ThriftStruct] extends ThriftStructCodec[T] {
  protected def ttypeToString(byte: Byte): String = ThriftStruct.ttypeToString(byte)
}

/**
 * Metadata for a method for a Thrift service.
 *
 * Comments below will use this example IDL:
 * {{{
 * service ExampleService {
 *   i32 boringMethod(
 *     1: i32 input1,
 *     2: string input2
 *   )
 * }
 * }}}
 */
trait ThriftMethod {

  /**
   * A struct wrapping method arguments
   *
   * For Scala generated code this will be a wrapper around all of the inputs.
   * Roughly:
   * {{{
   * class Args(input1: Int, input2: String) extends ThriftStruct
   * }}}
   */
  type Args <: ThriftStruct

  /**
   * The successful return type
   *
   * For Scala generated code this will be the response's type.
   * Roughly:
   * {{{
   * type SuccessType = Int
   * }}}
   */
  type SuccessType

  /** Contains success or thrift application exceptions */
  type Result <: ThriftResponse[SuccessType] with ThriftStruct

  // Note there is some indirection here for `FunctionType`, `ServicePerEndpointServiceType`,
  // and `ReqRepServicePerEndpointServiceType`. This is because for Scala generated with
  // Finagle bindings, these add dependencies on Twitter Util and Finagle.
  // This indirection allows us to sidestep that and keep scrooge-core free of those dependencies.

  /**
   * The type of this method, as a function.
   *
   * For Scala generated code with Finagle bindings this will be roughly:
   * {{{
   * Function1[Args, Future[Int]]
   * }}}
   *
   * For Scala generated code without Finagle bindings, this will be `Nothing`.
   */
  type FunctionType

  /**
   * The type of this method, as a function.
   *
   * For Scala generated code with Finagle bindings this will be roughly:
   * {{{
   * Function1[scrooge.Request[Args], Future[scrooge.Response[Int]]]
   * }}}
   *
   * For Scala generated code without Finagle bindings, this will be `Nothing`.
   */
  type ReqRepFunctionType

  /**
   * The type of this method, as a Finagle `Service` from `Args` to
   * `SuccessType`.
   *
   * For Scala generated code with Finagle bindings this will be roughly:
   * `Service[Args, SuccessType]`.
   *
   * For Scala generated code without Finagle bindings, this will be `Nothing`.
   */
  type ServicePerEndpointServiceType

  /**
   * The type of this method, as a Finagle `Service` from `scrooge.Request[Args]` to
   * `scrooge.Response[SuccessType]`.
   *
   * For Scala generated code with Finagle bindings this will be roughly:
   * `Service[scrooge.Request[Args], scrooge.Response[SuccessType]]`.
   *
   * For Scala generated code without Finagle bindings, this will be `Nothing`.
   */
  type ReqRepServicePerEndpointServiceType

  /**
   * Convert a function implementation of this method into a
   * ServicePerEndpoint Finagle `Service` implementation returning `SuccessType`.
   *
   * For Scala generated code without Finagle bindings, this will not implemented.
   */
  def toServicePerEndpointService(f: FunctionType): ServicePerEndpointServiceType

  /**
   * Convert a function implementation of this method into a
   * ReqRepServicePerEndpoint Finagle `Service` implementation returning
   * `scrooge.Response[SuccessType]`.
   *
   * For Scala generated code without Finagle bindings, this will not implemented.
   */
  def toReqRepServicePerEndpointService(f: ReqRepFunctionType): ReqRepServicePerEndpointServiceType

  /** Thrift annotations (user-defined key-value metadata) on the method */
  def annotations: scala.collection.immutable.Map[String, String]

  /** Thrift method name */
  def name: String

  /** Thrift service name. A thrift service is a list of methods. */
  def serviceName: String

  /** Codec for the request args */
  def argsCodec: ThriftStructCodec3[Args]

  /** Codec for the response */
  def responseCodec: ThriftStructCodec3[Result]

  /** True for oneway thrift methods */
  def oneway: Boolean
}

/**
 * A trait that provides an interface for building a new StructBuilder[T].
 *
 * When added to the companion object, it makes it possible to create a T statically
 * without needing to call `newBuilder()` on an instance. For example, calling
 * `Struct.newBuilder()`. In this case, there will be no default values set for each
 * field, making it necessary for the caller to set all the fields in the struct.
 */
trait StructBuilderFactory[T <: ThriftStruct] {

  /**
   * A builder to create a new instance of T.
   *
   * For default values:
   * - Call `newBuilder()` on an instance of the struct
   * - Set an individual field in the struct with `builder.setField(index, value)` while
   *   all other fields will be the same as the instance on which `newBuilder()` was called
   * - Set all the fields in the struct with `builder.setAllFields(seqOfValues)`
   *
   * For a static builder without any default values:
   * - Call `newBuilder()` on the struct object (i.e. `T.newBuilder()`)
   * - Set an individual field in the struct with `builder.setField(index, value)`. No
   *   other fields will be set so it is imperative that the caller sets all of the struct
   *   fields manually with `setField` or `setAllFields`
   * - Set all the fields in the struct with `builder.setAllFields(seqOfValues)`
   *
   * Finally, call `builder.build()` to build the new T.
   */
  def newBuilder(): StructBuilder[T]
}

/**
 * A class that provides an interface for building a new ThriftStruct from an existing
 * ThriftStruct or statically from T.
 *
 * We pass in a list of [[ClassTag]]s which describe each of the struct's field types
 * so that we can validate the values we are setting at runtime.
 */
abstract class StructBuilder[T <: ThriftStruct](fieldTypes: IndexedSeq[ClassTag[_]]) {
  protected val fieldArray: Array[Any] = new Array[Any](fieldTypes.size)

  /**
   * Add or update the field in the fieldArray.
   *
   * @param index the index of the field to add or update.
   * @param v the value of the field to add or update.
   * @param tag the class tag of the value to add or update.
   */
  private[this] def addOrUpdateFieldArray[A](index: Int, v: Any)(implicit tag: ClassTag[A]): Unit =
    v match {
      case inputValue: A => fieldArray(index) = inputValue
      case _ => throw new IllegalArgumentException(s"value at index $index must be of type $tag")
    }

  /**
   * The error message used when `build()` throws an InvalidFieldsException when
   * values are not present in the fieldArray or when the values are invalid.
   *
   * @param structName the name of the ThriftStruct to include in the error message.
   */
  protected def structBuildError(structName: String): String =
    s"All fields must be set using `StructBuilder[$structName].setField` " +
      s"or `StructBuilder[$structName].setAllFields` before calling " +
      s"`StructBuilder[$structName].build`."

  /**
   * Set the field at `index` with `value`.
   *
   * When called on a builder that was created from an existing instance of T,
   * all the other fields will be the same as that struct.
   *
   * When called on a static builder, the other fields will not have defaults so
   * the caller must ensure that all the other fields are set before calling `build()`.
   *
   * @param index the index of the field list for the ThriftStruct.
   * @param value the value to use for the field in ThriftStruct.
   */
  def setField(index: Int, value: Any): StructBuilder[T] = {
    addOrUpdateFieldArray(index, value)(fieldTypes(index))
    this
  }

  /**
   * Set all of the fields of the new ThriftStruct. A value must be present for
   * all fields required to construct the ThriftStruct and in the correct order.
   *
   * @param fields a sequence of values ordered by field index.
   */
  def setAllFields(fields: Seq[Any])(implicit tag: ClassTag[T]): StructBuilder[T] = {
    if (fields.size != fieldTypes.size) {
      throw new IndexOutOfBoundsException(
        s"The input must have ${fieldTypes.size} " +
          s"value(s), found ${fields.size}.")
    } else {
      fields.zipWithIndex.foreach {
        case (value, index) =>
          addOrUpdateFieldArray(index, value)(fieldTypes(index))
      }
    }
    this
  }

  /**
   * Build a new ThriftStruct after setting the desired fields.
   */
  def build(): T
}

/**
 * An exception used to communicate when the fields on a struct are set
 * incorrectly and the struct cannot be built because of it.
 */
final class InvalidFieldsException(message: String) extends Exception(message)
