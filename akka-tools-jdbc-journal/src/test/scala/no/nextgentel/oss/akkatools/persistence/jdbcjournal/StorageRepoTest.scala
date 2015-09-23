package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest._
import org.slf4j.LoggerFactory
import org.sql2o.Sql2o
import org.sql2o.quirks.OracleQuirks

class StorageRepoTest extends FunSuite with Matchers with BeforeAndAfterAll with BeforeAndAfter{

  val log = LoggerFactory.getLogger(getClass)
  lazy val dataSource = DataSourceUtil.createDataSource("StorageRepoTest", "akka-tools-jdbc-journal-liquibase.sql")

  val errorHandler = new JdbcJournalErrorHandler {
    override def onError(e: Exception): Unit = log.error("onError", e)
  }

  lazy val repo = new StorageRepoImpl(new Sql2o(dataSource, new OracleQuirks), None, errorHandler)

  val nextId = new AtomicInteger(0)

  def getNextId():String = nextId.incrementAndGet().toString

  test("writing and loading one dto") {
    val pid1 = ProcessorId("pId", getNextId())
    val dto1 = JournalEntryDto(pid1, 1L, "persistentRepr".getBytes, "This is the payload")

    repo.insertPersistentReprList(Seq(dto1))

    val read = repo.loadJournalEntries(pid1, 0, 1, 10)(0)

    // Must special-equals it due to the byte-array
    assert(dto1.persistentRepr.deep == read.persistentRepr.deep)
    assert(dto1.copy(persistentRepr = null, payloadWriteOnly = null) == read.copy(persistentRepr = null))
  }

  test("journal operations") {

    val pid1 = ProcessorId("pId", getNextId())

    assert( 0 == repo.findHighestSequenceNr(pid1, 0))

    val dto1 = JournalEntryDto(pid1, 1L, null, null)

    repo.insertPersistentReprList(Seq(dto1))
    assert( List() == repo.loadJournalEntries(pid1, 0, 0, 10))
    assert( List(dto1) == repo.loadJournalEntries(pid1, 0, 1, 10))
    assert( List(dto1) == repo.loadJournalEntries(pid1, 0, 2, 10))
    assert( List() == repo.loadJournalEntries(pid1, 0, 1, 0))

    assert( 1 == repo.findHighestSequenceNr(pid1, 0))

    val dto2 = JournalEntryDto(pid1, 2L, null, null)
    repo.insertPersistentReprList(Seq(dto2))

    assert( List() == repo.loadJournalEntries(pid1, 0, 0, 10))
    assert( List(dto1) == repo.loadJournalEntries(pid1, 0, 1, 10))
    assert( List(dto1, dto2) == repo.loadJournalEntries(pid1, 0, 2, 10))
    assert( List(dto2) == repo.loadJournalEntries(pid1, 2, 2, 10))
    assert( List(dto1) == repo.loadJournalEntries(pid1, 0, 2, 1))

    assert( 2 == repo.findHighestSequenceNr(pid1, 0))

    repo.deleteJournalEntryTo(pid1, 1)

    assert( List(dto2) == repo.loadJournalEntries(pid1, 0, 2, 10))

    repo.deleteJournalEntryTo(pid1, 2)
    assert( List() == repo.loadJournalEntries(pid1, 0, 2, 10))

    assert( 0 == repo.findHighestSequenceNr(pid1, 0))
  }

  // TODO: Write tests for snapshot- and cluster-code

}
