package no.nextgentel.oss.akkatools.serializing

trait JacksonJsonSerializable extends Serializable {}
trait JacksonJsonSerializableButNotDeserializable extends JacksonJsonSerializable {}
