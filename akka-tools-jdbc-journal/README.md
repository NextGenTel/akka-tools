Akka-persistence journal for JDBC (Oracle)
================================================

When used with Akka Persistence, all events and snapshots are written to Oracle.
 
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
      processorId                             VARCHAR(255),
      sequenceNr                              INT,
      timestamp                               NUMERIC,
      snapshot                                BLOB,
      snapshotClassname                       VARCHAR(255),
      updated                                 TIMESTAMP,
    
      PRIMARY KEY(processorId, sequenceNr, timestamp)
    );

    CREATE TABLE t_cluster_nodes (
        nodeName                              VARCHAR(255),
        lastSeen                              TIMESTAMP,
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
    
The payload of the events are written and read from the *persistentRepr*-column.

If the payload is serialized using *no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializer*,
a json-string-representation of the payload is also written to *payload_write_only* - This is only done
for readability and is never read/used in this code.


To enable this persistence-plugin, add the following to your akka *application.conf*-file:

    include classpath("akka-tools-json-serializing")
    

It would be a good idea to use this with the *JacksonJsonSerializer*-module, but it is not mandatory.

You have to initialize it with a working DataSource, schemaName and an objectMapper by calling this method before use:

    no.nextgentel.oss.akkatools.persistence.jdbcjournal.JdbcJournal.init( JdbcJournalConfig(dataSource, schemaName, fatalErrorHandler) );
    

Special PersistentView-feature
--------------------------------

This jdbc-journal has implemented a special feature that makes it possible to create a PersistentView that receives
ALL events for ALL instances of a specific type.

If you have the following eventsources acctors/aggregates:
  
   /data/car/1
   /data/car/2
   /data/car/3
   

If you create a PersistentView with '/data/car/1', it will get all events for that car.
But if you create a PersistentView with '/data/car/*', the special feature will give you all events for all cars.
All these events are wrapped in JournalEntry-objects