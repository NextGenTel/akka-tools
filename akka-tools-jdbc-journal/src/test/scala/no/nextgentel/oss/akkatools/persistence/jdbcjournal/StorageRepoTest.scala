package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest._
import org.slf4j.LoggerFactory
import org.sql2o.Sql2o
import org.sql2o.quirks.OracleQuirks

class StorageRepoTest extends FunSuite with Matchers with BeforeAndAfterAll with BeforeAndAfter{

  val log = LoggerFactory.getLogger(getClass)

  val errorHandler = new JdbcJournalErrorHandler {
    override def onError(e: Exception): Unit = log.error("onError", e)
  }

  lazy val repo = new StorageRepoImpl(new Sql2o(DataSourceUtil.createDataSource("StorageRepoTest"), new OracleQuirks), StorageRepoConfig(), Some(errorHandler))

  val nextId = new AtomicInteger(0)

  def getNextId():String = nextId.incrementAndGet().toString

  test("writing and loading one dto") {
    val pid1 = PersistenceIdSingle("pId", getNextId())
    val dto1 = JournalEntryDto(pid1.tag, pid1.uniqueId, 1L, "persistentRepr".getBytes, "This is the payload", null)

    repo.insertPersistentReprList(Seq(dto1))

    val read = repo.loadJournalEntries(pid1, 0, 1, 10)(0)

    // Must special-equals it due to the byte-array
    assert(dto1.persistentRepr.deep == read.persistentRepr.deep)
    assert( read.timestamp != null )
    assert(dto1.copy(persistentRepr = null, payloadWriteOnly = null) == read.copy(persistentRepr = null, timestamp = null))
  }

  test("journal operations") {

    // Must remove bytearray to please the case class equals
    def fix(dtos:List[JournalEntryDto]):List[JournalEntryDto] = {
      dtos.map {
        d =>
          d.copy(persistentRepr = null, timestamp = null)
      }
    }

    val pid1 = PersistenceIdSingle("pId", getNextId())

    assert( 0 == repo.findHighestSequenceNr(pid1, 0))

    val dummyPersistentRepr = Array[Byte]()

    val dto1 = JournalEntryDto(pid1.tag, pid1.uniqueId, 1L, dummyPersistentRepr, null, null)

    repo.insertPersistentReprList(Seq(dto1))
    assert( List() == repo.loadJournalEntries(pid1, 0, 0, 10))
    assert( fix(List(dto1)) == fix(repo.loadJournalEntries(pid1, 0, 1, 10)))
    assert( fix(List(dto1)) == fix(repo.loadJournalEntries(pid1, 0, 2, 10)))
    assert( List() == repo.loadJournalEntries(pid1, 0, 1, 0))

    assert( 1 == repo.findHighestSequenceNr(pid1, 0))

    val dto2 = JournalEntryDto(pid1.tag, pid1.uniqueId, 2L, dummyPersistentRepr, null, null)
    repo.insertPersistentReprList(Seq(dto2))

    assert( List() == repo.loadJournalEntries(pid1, 0, 0, 10))
    assert( fix(List(dto1)) == fix(repo.loadJournalEntries(pid1, 0, 1, 10)))
    assert( fix(List(dto1, dto2)) == fix(repo.loadJournalEntries(pid1, 0, 2, 10)))
    assert( fix(List(dto2)) == fix(repo.loadJournalEntries(pid1, 2, 2, 10)))
    assert( fix(List(dto1)) == fix(repo.loadJournalEntries(pid1, 0, 2, 1)))

    assert( 2 == repo.findHighestSequenceNr(pid1, 0))

    repo.deleteJournalEntryTo(pid1, 1)

    assert( fix(List(dto2)) == fix(repo.loadJournalEntries(pid1, 0, 2, 10)))

    repo.deleteJournalEntryTo(pid1, 2)
    assert( List() == repo.loadJournalEntries(pid1, 0, 2, 10))

    assert( 2 == repo.findHighestSequenceNr(pid1, 0))
  }

  // TODO: Write tests for snapshot- and cluster-code

}
