package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import akka.persistence.CapabilityFlag
import akka.persistence.journal.JournalSpec
import akka.persistence.snapshot.SnapshotStoreSpec
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import org.slf4j.LoggerFactory

class MyJournalSpec extends JournalSpec (
  config = ConfigFactory.load("application-test.conf")) {

  val log = LoggerFactory.getLogger(getClass)

  val errorHandler = new JdbcJournalErrorHandler {
    override def onError(e: Exception): Unit = log.error("JdbcJournalErrorHandler.onError", e)
  }

  // Remember: Since JdbcJournal.init() is static this will break if we run tests in parallel
  JdbcJournal.init(JdbcJournalConfig(DataSourceUtil.createDataSource("MyJournalSpec"), None, errorHandler, new PersistenceIdParserImpl('-')))

  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = false
}

class MySnapshotStoreSpec extends SnapshotStoreSpec (
  config = ConfigFactory.load("application-test.conf")) with BeforeAndAfter {

  val log = LoggerFactory.getLogger(getClass)

  val errorHandler = new JdbcJournalErrorHandler {
    override def onError(e: Exception): Unit = log.error("JdbcJournalErrorHandler.onError", e)
  }

  // Remember: Since JdbcJournal.init() is static this will break if we run tests in parallel
  JdbcJournal.init(JdbcJournalConfig(DataSourceUtil.createDataSource("MySnapshotStoreSpec"), None, errorHandler, new PersistenceIdParserImpl('-')))



}
