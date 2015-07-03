package no.nextgentel.oss.akkatools.serializing

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.typesafe.config.{ConfigFactory, Config}
import org.scalatest.{Matchers, FunSuite}

class JacksonJsonSerializerTest extends FunSuite with Matchers {

  val objectMapper = new ObjectMapper()

  test("serializer") {
    JacksonJsonSerializer.init(objectMapper)
    val serializer = new JacksonJsonSerializer()
    val a = Animal("our cat", 12, Cat("black", true))
    val bytes = serializer.toBinary(a)
    val ar = serializer.fromBinary(bytes, classOf[Animal]).asInstanceOf[Animal]
    assert( a == ar)
  }

  test("Registering the serializer works") {
    JacksonJsonSerializer.init(objectMapper)
    val system = ActorSystem("JacksonJsonSerializerTest", ConfigFactory.load("akka-tools-json-serializing.conf"))

    val serialization = SerializationExtension.get(system)
    assert( classOf[JacksonJsonSerializer] ==  serialization.serializerFor(classOf[Animal]).getClass)

    system.shutdown()
  }

  test("DepricatedTypeWithMigrationInfo") {
    JacksonJsonSerializer.init(objectMapper)
    val serializer = new JacksonJsonSerializer()
    val bytes = serializer.toBinary(OldType("12"))
    assert(NewType(12) == serializer.fromBinary(bytes, classOf[OldType]))
  }

  test("verifySerialization - no error") {
    JacksonJsonSerializer.init(objectMapper, true)
    val serializer = new JacksonJsonSerializer()
    val a = Animal("our cat", 12, Cat("black", true))
    val ow = ObjectWrapperWithTypeInfo(a)
    serializer.toBinary(ow)
  }

  test("verifySerialization - with error") {
    JacksonJsonSerializer.init(objectMapper, true)
    val serializer = new JacksonJsonSerializer()
    val a = Animal("our cat", 12, Cat("black", true))
    val ow = ObjectWrapperWithoutTypeInfo(a)
    intercept[JacksonJsonSerializerVerificationFailed] {
      serializer.toBinary(ow)
    }
  }

  test("verifySerialization - disabled") {
    JacksonJsonSerializer.init(objectMapper, true)
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