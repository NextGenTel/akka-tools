package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.time.{OffsetDateTime, ZoneId}
import java.util.Date
import javax.sql.DataSource

import no.nextgentel.oss.akkatools.cluster.ClusterNodeRepo
import org.slf4j.LoggerFactory
import org.sql2o.data.{Row, Table}
import org.sql2o._

import scala.concurrent.duration.FiniteDuration

case class JournalEntryDto(typePath:String, uniqueId:String, sequenceNr:Long, persistentRepr:Array[Byte], payloadWriteOnly:String, timestamp:OffsetDateTime)
case class SnapshotEntry(persistenceId:String, sequenceNr:Long, timestamp:Long, snapshot:Array[Byte], manifest:String, serializerId:Option[Int])


trait StorageRepo {
  def insertPersistentReprList(dtoList: Seq[JournalEntryDto]): Unit

  def deleteJournalEntryTo(persistenceId: PersistenceIdSingle, toSequenceNr: Long): Unit

  def loadJournalEntries(persistenceId: PersistenceId, fromSequenceNr: Long, toSequenceNr: Long, max: Long): List[JournalEntryDto]

  def findHighestSequenceNr(persistenceId: PersistenceId, fromSequenceNr: Long): Long

  def writeSnapshot(snapshotEntry: SnapshotEntry): Unit

  def findSnapshotEntry(persistenceId: String, maxSequenceNr: Long, maxTimestamp: Long): Option[SnapshotEntry]

  def deleteSnapshot(persistenceId: String, sequenceNr: Long, timestamp: Long): Unit

  def deleteSnapshotsMatching(persistenceId: String, maxSequenceNr: Long, maxTimestamp: Long): Unit
}

trait StorageRepoWithClusterNodeRepo extends StorageRepo with ClusterNodeRepo

trait JdbcJournalErrorHandler {
  def onError(e:Exception): Unit
}

class JdbcJournalDetectFatalOracleErrorHandler(fatalErrorHandler:JdbcJournalErrorHandler) extends JdbcJournalErrorHandler {
  override def onError(e: Exception): Unit = {
    if (e.getMessage != null && e.getMessage.contains("ORA-00001: unique constraint")) {
      fatalErrorHandler.onError(e)
    }
  }
}

case class StorageRepoConfig
(
  schemaName: Option[String] = None,
  tableName_journal:String = "t_journal",
  sequenceName_journalIndex:String = "s_journalIndex_seq",
  tableName_snapshot:String = "t_snapshot"

)

