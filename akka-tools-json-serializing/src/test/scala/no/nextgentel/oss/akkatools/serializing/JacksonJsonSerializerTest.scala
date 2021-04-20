package no.nextgentel.oss.akkatools.serializing

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.{ObjectMapper, SerializationFeature}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.funsuite.AnyFunSuite

class JacksonJsonSerializerTest extends AnyFunSuite /*with Matchers*/ {

  val objectMapper = new ObjectMapper()
  objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
  objectMapper.registerModule(new DefaultScalaModule)

  test("serializer") {
    JacksonJsonSerializer.setObjectMapper(objectMapper)
    val serializer = new JacksonJsonSerializer()
    val a = Animal("our cat", 12, Cat("black", true))
    val bytes = serializer.toBinary(a)
    val ar = serializer.fromBinary(bytes, classOf[Animal]).asInstanceOf[Animal]
    assert( a == ar)
  }

  test("Registering the serializer works") {
    JacksonJsonSerializer.setObjectMapper(objectMapper)
    val system = ActorSystem("JacksonJsonSerializerTest", ConfigFactory.load("akka-tools-json-serializing.conf"))

    val serialization = SerializationExtension.get(system)
    assert( classOf[JacksonJsonSerializer] ==  serialization.serializerFor(classOf[Animal]).getClass)

    system.terminate()
  }

  test("DepricatedTypeWithMigrationInfo") {
    JacksonJsonSerializer.setObjectMapper(objectMapper)
    val serializer = new JacksonJsonSerializer()
    val bytes = serializer.toBinary(OldType("12"))
    assert(NewType(12) == serializer.fromBinary(bytes, classOf[OldType]))
  }

  test("verifySerialization - no error") {
    JacksonJsonSerializer.setObjectMapper(objectMapper)
    JacksonJsonSerializer.setVerifySerialization(true)
    val serializer = new JacksonJsonSerializer()
    val a = Animal("our cat", 12, Cat("black", true))
    val ow = ObjectWrapperWithTypeInfo(a)
    serializer.toBinary(ow)
  }

  test("verifySerialization - with error") {
    JacksonJsonSerializer.setObjectMapper(objectMapper)
    JacksonJsonSerializer.setVerifySerialization(true)
    val serializer = new JacksonJsonSerializer()
    val a = Animal("our cat", 12, Cat("black", true))
    val ow = ObjectWrapperWithoutTypeInfo(a)
    intercept[JacksonJsonSerializerVerificationFailed] {
      serializer.toBinary(ow)
    }
  }

  test("verifySerialization - disabled") {
    JacksonJsonSerializer.setObjectMapper(objectMapper)
    JacksonJsonSerializer.setVerifySerialization(true)
    val serializer = new JacksonJsonSerializer()
    val a = Animal("our cat", 12, Cat("black", true))
    val ow = ObjectWrapperWithoutTypeInfoOverrided(a)
    serializer.toBinary(ow)
  }



}

case class Animal(name:String, age:Int, t:Cat) extends JacksonJsonSerializable

case class Cat(color:String, tail:Boolean)

case class OldType(s:String) extends DepricatedTypeWithMigrationInfo {
  override def convertToMigratedType(): AnyRef = NewType(s.toInt)
}
case class NewType(i:Int)


case class ObjectWrapperWithTypeInfo(@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@any_class") any:AnyRef)

case class ObjectWrapperWithoutTypeInfo(any:AnyRef)

case class ObjectWrapperWithoutTypeInfoOverrided(any:AnyRef) extends JacksonJsonSerializableButNotDeserializable