package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import akka.persistence.CapabilityFlag
import akka.persistence.journal.JournalSpec
import akka.persistence.snapshot.SnapshotStoreSpec
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import org.slf4j.LoggerFactory

class MyJournalSpec extends JournalSpec (
  config = ConfigFactory.parseString(
    s"""
       |akka.persistence.query.jdbc-read-journal.configName = MyJournalSpec
       |jdbc-journal.configName = MyJournalSpec
       |jdbc-snapshot-store.configName = MyJournalSpec
     """.stripMargin).withFallback(ConfigFactory.load("application-test.conf"))) {

  val log = LoggerFactory.getLogger(getClass)

  val errorHandler = new JdbcJournalErrorHandler {
    override def onError(e: Exception): Unit = log.error("JdbcJournalErrorHandler.onError", e)
  }

  JdbcJournalConfig.setConfig("MyJournalSpec", JdbcJournalConfig(DataSourceUtil.createDataSource("MyJournalSpec"), None, errorHandler, new PersistenceIdParserImpl('-')))

  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = false
}

class MySnapshotStoreSpec extends SnapshotStoreSpec (
  config = ConfigFactory.parseString(
    s"""
       |akka.persistence.query.jdbc-read-journal.configName = MySnapshotStoreSpec
       |jdbc-journal.configName = MySnapshotStoreSpec
       |jdbc-snapshot-store.configName = MySnapshotStoreSpec
     """.stripMargin).withFallback(ConfigFactory.load("application-test.conf"))) with BeforeAndAfter {

  val log = LoggerFactory.getLogger(getClass)

  val errorHandler = new JdbcJournalErrorHandler {
    override def onError(e: Exception): Unit = log.error("JdbcJournalErrorHandler.onError", e)
  }

  JdbcJournalConfig.setConfig("MySnapshotStoreSpec", JdbcJournalConfig(DataSourceUtil.createDataSource("MySnapshotStoreSpec"), None, errorHandler, new PersistenceIdParserImpl('-')))

}
