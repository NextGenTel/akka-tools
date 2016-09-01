package no.nextgentel.oss.akkatools.serializing

import java.io.{IOException, NotSerializableException}

import akka.serialization.Serializer
import com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.slf4j.LoggerFactory

object JacksonJsonSerializer {

  private var _objectMapper:Option[ObjectMapper] = None

  // Should only be used during testing
  // When true, all objects being serialized are also deserialized and compared
  private var verifySerialization: Boolean = false

  @Deprecated //For Java-compiler
  @deprecated("Use setObjectMapper() - NB! You now have to configure objectMapper your self!! - and setVerifySerialization() instead", "1.0.3")
  def init(m:ObjectMapper, verifySerialization:Boolean = false): Unit = {
    def configureObjectMapper(mapper:ObjectMapper):ObjectMapper = {
      mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
      mapper.registerModule(new DefaultScalaModule)
      mapper
    }

    setObjectMapper(configureObjectMapper(m.copy()))
    setVerifySerialization(verifySerialization)
  }

  def setObjectMapper(preConfiguredObjectMapper:ObjectMapper):Unit = {
    _objectMapper = Some(preConfiguredObjectMapper)
  }

  def setVerifySerialization(verifySerialization:Boolean): Unit = {
    this.verifySerialization = verifySerialization
    if (verifySerialization) {
      val logger = LoggerFactory.getLogger(getClass)
      logger.warn("*** Performance-warning: All objects being serialized are also deserialized and compared. Should only be used during testing")
    }
  }

  protected def objectMapper():ObjectMapper = {
    _objectMapper.getOrElse(throw new Exception(getClass().toString + " has not been with an initialized with an objectMapper. You must call init(objectMapper) before using the serializer"))
  }
}

class JacksonJsonSerializerException(errorMsg:String, cause:Throwable) extends NotSerializableException() {
  // Must extend NotSerializableException so that we can prevent akka-remoting connections being dropped
  override def getMessage: String = errorMsg
  override def getCause: Throwable = cause
}

class JacksonJsonSerializerVerificationFailed(errorMsg:String) extends JacksonJsonSerializerException(errorMsg, null)

class JacksonJsonSerializer extends Serializer {
  val logger = LoggerFactory.getLogger(getClass)
  import JacksonJsonSerializer._



  // The serializer id has to have this exact value to be equal to the old original implementation
  override def identifier: Int = 67567521

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
      case d:JacksonJsonSerializableButNotDeserializable =>
        throw new Exception("The type " + o.getClass + " is not supposed to be deserializable since it extends JacksonJsonSerializableButNotDeserializable")
      case x:AnyRef => x
    }
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    try {
      if (logger.isDebugEnabled) {
        logger.debug("toBinary: " + o.getClass)
      }
      val bytes: Array[Byte] = objectMapper().writeValueAsBytes(o)
      if (verifySerialization) {
        doVerifySerialization(o, bytes)
      }
      bytes
    } catch {
      case e:JacksonJsonSerializerException =>
        throw e
      case e:Exception =>
        throw new JacksonJsonSerializerException(e.getMessage, e)
    }
  }

  private def doVerifySerialization(originalObject: AnyRef, bytes: Array[Byte]):Unit = {
    if (originalObject.isInstanceOf[JacksonJsonSerializableButNotDeserializable]) {
      if (logger.isDebugEnabled) {
        logger.debug("Skipping doVerifySerialization: " + originalObject.getClass)
      }
      return ;
    }
    if (logger.isDebugEnabled) {
      logger.debug("doVerifySerialization: " + originalObject.getClass)
    }
    val deserializedObject: AnyRef = fromBinary(bytes, originalObject.getClass)
    if (!(originalObject == deserializedObject)) {
      throw new JacksonJsonSerializerVerificationFailed("Serialization-verification failed.\n" + "original:     " + originalObject.toString + "\n" + "deserialized: " + deserializedObject.toString)
    }
  }
}
