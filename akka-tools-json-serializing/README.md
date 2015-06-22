Akka-serializer using Json
================================

This is an Akka Serializer implementation that uses Jackson Json.

When registered into Akka, it will serializes/deserializes all classes which implements:

    no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializable

Before use, you need to give it an objectMapper to use like this:

    no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializer.init(objectMapper);
    
To register it, add the following to your akka application.conf:

    include classpath("akka-tools-json-serializing")
    
If used from UnitTests, you may want to turn on **verifySerialization** which asserts that serializing/deserializing works as expected. 


Data-structure Migration
----------------------

Even though Json is a good match for evolving data-structures, you might have the need 
for a big refactoring of your app.

In such cases you need to have a strategy about how to handle old events written to the journal using old format of events.

In such cases you should have a look at the trait **DepricatedTypeWithMigrationInfo**.

By using this trait, you still have the code for your old event-types, but if they extends DepricatedTypeWithMigrationInfo, 
the serializer will execute **convertToMigratedType()** where you have coded the convertion to the new event-type-representation.



