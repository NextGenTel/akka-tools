Akka-serializer using Json
================================

Akka serializer which registers into Akka and serializes/deserializes all classes which implements:

    no.ngt.oss.akkatools.serializing.AkkaJsonSerializable

Before use, you need to give it an objectMapper to use like this:

    no.ngt.oss.akkatools.serializing.NgtJsonSerializer.init(objectMapper);
    
To register it, add the following to your akka application.conf:

    include classpath("akka-tools-json-serializing")
    
The serializer also have the trait **DepricatedTypeWithMigrationInfo** which is very usefull when
migrating from old to new datastructures.
