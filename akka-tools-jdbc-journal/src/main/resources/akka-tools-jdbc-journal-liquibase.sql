--changeset mokj:Create-akka-tools-jdbc-journal-tables dbms:all
CREATE TABLE t_journal (
  typePath                                VARCHAR(255),
  id                                      VARCHAR(255),
  sequenceNr                              BIGINT,
  journalIndex                            BIGINT,
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
  sequenceNr                              BIGINT,
  timestamp                               NUMERIC,
  snapshot                                BLOB,
  snapshotClassname                       VARCHAR(255),
  updated                                 TIMESTAMP,
  serializerId                            BIGINT,

  PRIMARY KEY(persistenceId, sequenceNr, timestamp)
);

CREATE TABLE t_cluster_nodes (
    nodeName                              VARCHAR(255),
    lastSeen                              TIMESTAMP,
    joined                                BIGINT,
    PRIMARY KEY(nodeName)
);