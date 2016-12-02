Migrating source from 1.0.x to 1.1.x
--------------------------------------

* PersistenceIdSplitterLastSlashImpl is renamed to PersistenceIdParserImpl
* Instead of SingletonJdbcJournalRuntimeDataFactory.init, use JdbcJournalConfig.setConfig(JdbcJournalConfig(...))
* ALTER TABLE t_snapshot RENAME COLUMN processorId TO persistenceId;
