Akka-persistence journal for JDBC (Oracle)
================================================

When used with Akka Persistence, all events and snapshots are written to Oracle.

A nice feature with this Journal-implementation is that, if used with the JacksonJsonSerializer, it will also write the events as human-readable json.
 
To enable this persistence-plugin, add the following to your akka *application.conf*-file:

    include classpath("akka-tools-json-serializing")

Before start using it, you have to initialize it with a working DataSource, schemaName and an fatalErrorHandler:

    import no.nextgentel.oss.akkatools.persistence.jdbcjournal.JdbcJournal
    
    val fatalErrorHandler = new JdbcJournalErrorHandler {
        override def onError(e: Exception): Unit = {
          log.error("Something bad happened", e)
          // Quit or restart the node..
        }
    }
    
    JdbcJournalConfig.setConfig("default", JdbcJournalConfig(dataSource, schemaName, fatalErrorHandler) )
    
Persistence Query
----------------------------
This journal also includes a Persistence Query Read Journal

This is how you can get a reference to the read journal:
    
    val readJournal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.identifier)
    
Get a live stream of all events for a specific persistenceId:

    val source = readJournal.eventsByPersistenceId(persistenceId, 0, Long.MaxValue)
    // materialize stream, consuming events
    implicit val mat = ActorMaterializer()
    source.runForeach {
      event =>
        println("Stream received Event: " + event)
    }
    
    
Get a stream of all events for a specific persistenceId - which stops when all current events are read:

    val source = readJournal.currentEventsByPersistenceId(persistenceId, 0, Long.MaxValue)
    
Get a live stream of all events for a specific type/tag of aggregates/PersistentActors
(Look at PersistenceIdParser to understand how this works)

    val source = readJournal.eventsByTag("booking", 0)
    
Get a live stream of all events from multiple type/tags
    
    val source = readJournal.eventsByTag("booking|customer", 0)
    
Get a stream of all events for a specific type/tag of aggregates/PersistentActors - which stops when all current events are read
(Look at PersistenceIdParser to understand how this works) 

    val source = readJournal.currentEventsByTag(tag, 0)
       
       
PersistenceIdParser
------------------------

Akka's PersistentActor-support only cares about the unique persistenceId of one specific Aggregate/PersistentActor.
It has no concept of the type.

akka-tools-jdbc-journal uses a PersistenceIdParser which knows how to split the persistenceId into both
tag and unique Id. The default impl uses the last '/' as the separator.

So if the your persistenceId looks like this:

    booking/12
    
Then this would result in type/tag = 'booking' and id = '12'

The journal write these two values into separate columns in the database.

This makes it possible to ask for all events for 'booking' or

If using Persistence Query's eventsByTag, your ask for the tag = 'booking'



Database schema
-------------------------
 
**Note: The name 'processorId' is, for historical reasons (Akka 2.3), the same as persistenceId**

The following tables are needed, here described in liquibase-format:

    --changeset mokj:Create-akka-tools-jdbc-journal-tables dbms:all
    CREATE TABLE t_journal (
      typePath                                VARCHAR(255),
      id                                      VARCHAR(255),
      sequenceNr                              INT,
      journalIndex                            INT,
      persistentRepr                          BLOB,
      payload_write_only                      CLOB,
      updated                                 TIMESTAMP,
    
      PRIMARY KEY(typePath, id, sequenceNr)
    );
    
    CREATE SEQUENCE s_journalIndex_seq START WITH 1;
    
    -- Create index to make it fast to query using only typePath and s_journal_global_seq
    CREATE UNIQUE INDEX IX_journalIndex ON t_journal(typePath, journalIndex);
    
    
    CREATE TABLE t_snapshot (
      persistenceId                           VARCHAR(255),
      sequenceNr                              INT,
      timestamp                               NUMERIC,
      snapshot                                BLOB,
      snapshotClassname                       VARCHAR(255),
      updated                                 TIMESTAMP,
      serializerId                            INT,
    
      PRIMARY KEY(persistenceId, sequenceNr, timestamp)
    );
    
    CREATE TABLE t_cluster_nodes (
        nodeName                              VARCHAR(255),
        lastSeen                              TIMESTAMP,
        joined                                INT,
        PRIMARY KEY(nodeName)
    );
    
You need the following grants:
    
    GRANT SELECT, INSERT, UPDATE, DELETE    ON LIQ_YOUR_SERVICE.t_journal TO APP_YOUR_SERVICE;
    GRANT SELECT, INSERT, UPDATE, DELETE    ON LIQ_YOUR_SERVICE.t_snapshot TO APP_YOUR_SERVICE;
    GRANT SELECT                            ON LIQ_YOUR_SERVICE.s_journalIndex_seq TO APP_YOUR_SERVICE;
    GRANT SELECT, INSERT, UPDATE, DELETE    ON LIQ_YOUR_SERVICE.t_cluster_nodes TO APP_YOUR_SERVICE;


If migrating from Akka 2.3.x to 2.4 you might want to apply the following db-change to remove a deprecated column (But it should still work if you leave it there). 

    ALTER TABLE t_journal DROP COLUMN redeliveries;
    ALTER TABLE t_journal DROP COLUMN deleted;

If migrating from **akka-tools 1.0.5 or earlier**, you need to apply the following DB-changes which was added in **akka-tools 1.0.6**:

    ALTER TABLE t_snapshot ADD serializerId INT;

If migrating from **akka-tools 1.0.6 or earlier**, you need to apply the following DB-changes which was added in **akka-tools 1.0.7**:

    ALTER TABLE t_cluster_nodes ADD joined INT;

If migrating from **akka-tools 1.0.x**, you need to apply the following DB-changes which was added in **akka-tools 1.1.0**:

    ALTER TABLE t_snapshot RENAME COLUMN processorId TO persistenceId;

The payload of the events are written and read from the *persistentRepr*-column.

If the payload is serialized using *no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializer*,
a json-string-representation of the payload is also written to *payload_write_only* - This is only done
for readability and is never read/used in this code.


It would be a good idea to use this with the *JacksonJsonSerializer*-module, but it is not mandatory.