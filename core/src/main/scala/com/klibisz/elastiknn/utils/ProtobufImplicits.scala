package com.klibisz.elastiknn.utils

import scalapb.descriptors._
import scalapb.{GeneratedMessage, GeneratedMessageCompanion, Message}

import scala.collection.JavaConverters._

trait ProtobufImplicits {

  private lazy val b64Decoder = java.util.Base64.getDecoder
  private lazy val b64Encoder = java.util.Base64.getEncoder

  implicit class GeneratedMessageImplicits(gm: GeneratedMessage) {

    /** Convert to a weakly typed map. Convenient for Elasticsearch APIs which use java.util.Map[String,Object]. */
    def asJavaMap: java.util.Map[String, Any] = {

      def convert(pv: PValue): Any = pv match {
        case PEmpty         => null
        case PInt(i)        => i
        case PLong(l)       => l
        case PString(s)     => s
        case PDouble(d)     => d
        case PFloat(f)      => f
        case PByteString(b) => b
        case PBoolean(b)    => b
        case PEnum(e)       => e.index
        case PRepeated(r)   => r.map(convert).asJava
        case PMessage(m)    => convertMap(m)
      }

      def convertMap(m: Map[FieldDescriptor, PValue]): java.util.Map[String, Any] =
        m.map {
          case (fd: FieldDescriptor, pv: PValue) => fd.scalaName -> convert(pv)
        }.asJava

      convertMap(gm.toPMessage.value)
    }

    def toBase64String: String = b64Encoder.encodeToString(gm.toByteArray)

    def toBase64Bytes: Array[Byte] = b64Encoder.encode(gm.toByteArray)

  }

  implicit class GeneratedCompanionImplicits[M <: GeneratedMessage with Message[M]](cmp: GeneratedMessageCompanion[M]) {
    def parseBase64(s: String): M = cmp.parseFrom(b64Decoder.decode(s))
    def parseBase642(barr: Array[Byte]): M = cmp.parseFrom(b64Decoder.decode(barr))
  }

}

object ProtobufImplicits extends ProtobufImplicits