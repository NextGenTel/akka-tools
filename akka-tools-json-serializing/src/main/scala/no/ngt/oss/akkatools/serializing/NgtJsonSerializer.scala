package no.ngt.oss.akkatools.serializing

import java.io.IOException

import akka.serialization.Serializer
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.{SerializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import no.ngt.oss.akkatools.serializing.NgtJsonSerializer._
import org.slf4j.LoggerFactory

object NgtJsonSerializer {

  private var _objectMapper:Option[ObjectMapper] = None

  // Should only be used during testing
  // When true, all objects being serialized are also deserialized and compared
  private var verifySerialization: Boolean = false

  def init(m:ObjectMapper, verifySerialization:Boolean = false): Unit = {
    _objectMapper = Some(configureObjectMapper(m.copy()))
    this.verifySerialization = verifySerialization
    if (verifySerialization) {
      val logger = LoggerFactory.getLogger(getClass)
      logger.warn("*** Performance-warning: All objects being serialized are also deserialized and compared. Should only be used during testing")
    }
  }

  def configureObjectMapper(mapper:ObjectMapper):ObjectMapper = {
    mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
    mapper.registerModule(new DefaultScalaModule)
    mapper
  }

  protected def objectMapper():ObjectMapper = {
    _objectMapper.getOrElse(throw new Exception(getClass().toString + " has not been with an initialized with an objectMapper. You must call init(objectMapper) before using the serializer"))
  }
}
// TODO: Need mechanism for backward compatibility

class NgtJsonSerializer extends Serializer {
  val logger = LoggerFactory.getLogger(getClass)
  import NgtJsonSerializer._



  override def identifier: Int = 67567522

  override def includeManifest: Boolean = true

  override def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val clazz:Class[_] = manifest.get
    if (logger.isDebugEnabled) logger.debug("fromBinaryJava: " + clazz)

    val o = objectMapper().readValue(bytes, clazz).asInstanceOf[AnyRef]

    o match {
      case d:DepricatedTypeWithMigrationInfo =>
        val m = d.convertToMigratedType()
        if (logger.isDebugEnabled) logger.debug("fromBinaryJava: " + clazz + " was migrated to " + m.getClass)
        m
      case x:AnyRef => x
    }
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    if (logger.isDebugEnabled) {
      logger.debug("toBinary: " + o.getClass)
    }
    val bytes: Array[Byte] = objectMapper().writeValueAsBytes(o)
    if (verifySerialization) {
      doVerifySerialization(o, bytes)
    }
    return bytes
  }

  private def doVerifySerialization(originalObject: AnyRef, bytes: Array[Byte]) {
    if (logger.isDebugEnabled) {
      logger.debug("doVerifySerialization: " + originalObject.getClass)
    }
    val deserializedObject: AnyRef = fromBinary(bytes, originalObject.getClass)
    if (!(originalObject == deserializedObject)) {
      throw new RuntimeException("Serialization-verification failed.\n" + "original:     " + originalObject.toString + "\n" + "deserialized: " + deserializedObject.toString)
    }
  }
}
