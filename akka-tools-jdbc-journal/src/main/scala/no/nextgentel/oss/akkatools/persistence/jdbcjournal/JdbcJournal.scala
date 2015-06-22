package no.nextgentel.oss.akkatools.persistence.jdbcjournal

object ProcessorIdType extends Enumeration {
  type ProcessorIdType = Value
  val FULL = Value
  val ONLY_TYPE = Value
}

import java.nio.charset.Charset
import java.time.OffsetDateTime
import javax.sql.DataSource

import ProcessorIdType._
import akka.actor.ActorLogging
import akka.persistence.PersistentConfirmation
import akka.persistence.PersistentId
import akka.persistence.PersistentRepr
import akka.persistence.SelectedSnapshot
import akka.persistence.SnapshotMetadata
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.journal.SyncWriteJournal
import akka.persistence.snapshot.SnapshotStore
import akka.serialization.SerializationExtension
import no.nextgentel.oss.akkatools.cluster.ClusterNodeRepo
import org.sql2o.Sql2o
import org.sql2o.quirks.OracleQuirks

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration

case class ProcessorId(private val _typePath: String, private val _id: String, private val _processorIdType: ProcessorIdType = FULL) {

  def processorIdType() = _processorIdType

  def typePath() = _typePath

  def id(): String = {
    if (!isFull()) {
      throw new RuntimeException("Cannot get Id-part when processorIdType is not FULL")
    } else {
      _id
    }
  }

  def isFull() = FULL == _processorIdType

  override def toString: String = {
    "ProcessorId{" + "typePath='" + typePath + '\'' + (if (isFull) (", id='" + id + '\'') else "") + '}'
  }

}

trait ProcessorIdSplitter {
  def split(processorId: String): ProcessorId
}

class ProcessorIdSplitterDefaultAkkaImpl extends ProcessorIdSplitter {
  def split(processorId: String): ProcessorId = {
    return new ProcessorId(processorId, "")
  }
}

object ProcessorIdSplitterLastSlashImpl {
  val WILDCARD: String = "*"
}

class ProcessorIdSplitterLastSlashImpl extends ProcessorIdSplitter {
  def split(processorId: String): ProcessorId = {
    val i: Int = processorId.lastIndexOf('/')
    if (i < 0) {
      return new ProcessorId(processorId, "")
    } else {
      val typePath: String = processorId.substring(0, i + 1)
      val id: String = processorId.substring(i + 1, processorId.length)
      if (ProcessorIdSplitterLastSlashImpl.WILDCARD == id) {
        return ProcessorId(typePath, null, ONLY_TYPE)
      } else {
        return ProcessorId(typePath, id)
      }
    }
  }
}

object JdbcJournalConfig {

  // Java helper
  def create(dataSource: DataSource, schemaName: String, fatalErrorHandler: JdbcJournalErrorHandler) = JdbcJournalConfig(dataSource, schemaName, fatalErrorHandler)
}

case class JdbcJournalConfig(dataSource: DataSource, schemaName: String, fatalErrorHandler: JdbcJournalErrorHandler, processorIdSplitter: ProcessorIdSplitter = new ProcessorIdSplitterLastSlashImpl(), maxRowsPrRead: Int = JdbcJournal.DEFAULT_MAX_ROWS_PR_READ)

object JdbcJournal {
  val DEFAULT_MAX_ROWS_PR_READ = 1000
  private var _repo: Option[StorageRepo] = None
  private var _processorIdSplitter: Option[ProcessorIdSplitter] = None
  var maxRowsPrRead = DEFAULT_MAX_ROWS_PR_READ

  val jacksonJsonSerializer_className = "no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializer"

  // This must be called at startup, before any akka-persistence-code is executed
  def init(config: JdbcJournalConfig): Unit = {
    val errorHandler = new JdbcJournalDetectFatalOracleErrorHandler(config.fatalErrorHandler)
    _repo = Some(new StorageRepoImpl(new Sql2o(config.dataSource, new OracleQuirks()), config.schemaName, errorHandler))
    _processorIdSplitter = Some(config.processorIdSplitter)
  }

  def repo(): StorageRepo = _repo.getOrElse(throw new Exception("JdbcJournal not configured yet"))
  def clusterNodeRepo() = repo().asInstanceOf[ClusterNodeRepo]

  def processorIdSplitter(): ProcessorIdSplitter = _processorIdSplitter.getOrElse(throw new Exception("JdbcJournal not configured yet"))
}

class JdbcSnapshotStore extends SnapshotStore with ActorLogging {

  import JdbcJournal._

  val serialization = SerializationExtension.get(context.system)

  override def loadAsync(processorId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    if (log.isDebugEnabled) {
      log.debug("JdbcSnapshotStore - doLoadAsync: " + processorId + " criteria: " + criteria)
    }

    val promise = Promise[Option[SelectedSnapshot]]()
    try {
      repo().findSnapshotEntry(processorId, criteria.maxSequenceNr, criteria.maxTimestamp) match {
        case None =>
          if (log.isDebugEnabled) {
            log.debug("JdbcSnapshotStore - doLoadAsync: Not found - " + processorId + " criteria: " + criteria)
          }
          promise.success(None)
        case Some(e: SnapshotEntry) =>

          val clazz: Class[_] = getClass().getClassLoader().loadClass(e.snapshotClassname)
          val snapshot = serialization.serializerFor(clazz).fromBinary(e.snapshot, clazz)

          SelectedSnapshot(
            new SnapshotMetadata(
              e.processorId,
              e.sequenceNr,
              e.timestamp),
            snapshot)
      }
    } catch {
      case e: Exception =>
        val errorMessage = "Error loading snapshot " + processorId + " - " + criteria
        log.error(e, errorMessage)
        promise.failure(new Exception(errorMessage, e));
    }

    promise.future
  }

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    val promise = Promise[Unit]()

    if (log.isDebugEnabled) {
      log.debug("JdbcSnapshotStore - doSaveAsync: " + metadata + " snapshot: " + snapshot)
    }

    try {

      val bytes = serialization.serializerFor(snapshot.getClass).toBinary(snapshot.asInstanceOf[AnyRef])

      repo().writeSnapshot(
        new SnapshotEntry(
          metadata.persistenceId,
          metadata.sequenceNr,
          metadata.timestamp,
          bytes,
          snapshot.getClass.getName))
      promise.success()
    } catch {
      case e: Exception => {
        val errorMessage: String = "Error storing snapshot"
        log.error(e, errorMessage)
        promise.failure(new Exception(errorMessage, e))
      }
    }

    promise.future
  }

  override def saved(metadata: SnapshotMetadata): Unit = {
    if (log.isDebugEnabled) {
      log.debug("JdbcSnapshotStore - onSaved: successfully saved: " + metadata)
    }
  }

  override def delete(metadata: SnapshotMetadata): Unit = {
    if (log.isDebugEnabled) {
      log.debug("JdbcSnapshotStore - doDelete: " + metadata)
    }

    repo().deleteSnapshot(metadata.persistenceId, metadata.sequenceNr, metadata.timestamp)
  }

  override def delete(processorId: String, criteria: SnapshotSelectionCriteria): Unit = {
    if (log.isDebugEnabled) {
      log.debug("JdbcSnapshotStore - doDelete: " + processorId + " criteria " + criteria)
    }

    repo().deleteSnapshotsMatching(processorId, criteria.maxSequenceNr, criteria.maxTimestamp)
  }
}

case class JournalEntry(processorId: ProcessorId, payload: AnyRef) {
  def payloadAs[T](): T = payload.asInstanceOf[T]
}

case class JsonObjectHolder(t:String, o:AnyRef)

class JdbcSyncWriteJournal extends SyncWriteJournal with ActorLogging {

  import JdbcJournal._

  val serialization = SerializationExtension.get(context.system)

  override def writeMessages(messages: Seq[PersistentRepr]): Unit = {
    messages.foreach {
      p =>
        if (log.isDebugEnabled) {
          log.debug("JdbcSyncWriteJournal doWriteMessages: persistentRepr: " + p)
        }

        val payloadJson = tryToExtractPayloadAsJson(p)
        val entry = JournalEntryDto(processorIdSplitter().split(p.processorId), p.sequenceNr, serialization.serialize(p).get, p.deleted, p.redeliveries, payloadJson.getOrElse(null))
        repo().updatePersistenRepr(entry)
    }
  }

  def tryToExtractPayloadAsJson(p: PersistentRepr): Option[String] = {
    // If we can use the no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializer on the payload,
    // we get the json as string, to make the data more visual in the db.
    val payload = p.payload.asInstanceOf[AnyRef]
    val serializer = serialization.serializerFor(payload.getClass)
    if (jacksonJsonSerializer_className == serializer.getClass.getName) {
      // we can do it
      val bytes = serializer.toBinary( JsonObjectHolder(payload.getClass.getName, payload))
      val json = new String(bytes, Charset.forName("UTF-8"))
      Some(json)
    } else {
      None
    }
  }

  override def deleteMessagesTo(processorId: String, toSequenceNr: Long, permanent: Boolean): Unit = {
    if (log.isDebugEnabled) {
      log.debug("JdbcSyncWriteJournal doDeleteMessagesTo: processorId: " + processorId + " toSequenceNr=" + toSequenceNr + " permanent=" + permanent)
    }

    repo().deleteJournalEntryTo(processorIdSplitter().split(processorId), toSequenceNr, permanent)
  }

  @deprecated("deleteMessages will be removed.")
  override def deleteMessages(messageIds: Seq[PersistentId], permanent: Boolean): Unit = throw new Exception("Method is deprecated and therefor not supported")

  @deprecated("writeConfirmations will be removed, since Channels will be removed.")
  override def writeConfirmations(confirmations: Seq[PersistentConfirmation]): Unit = throw new Exception("Method is deprecated and therefor not supported")

  override def asyncReadHighestSequenceNr(processorId: String, fromSequenceNr: Long): Future[Long] = {
    val promise = Promise[Long]()

    val highestSequenceNr = repo().findHighestSequenceNr(processorIdSplitter().split(processorId), fromSequenceNr)
    if (log.isDebugEnabled) {
      log.debug("JdbcSyncWriteJournal doAsyncReadHighestSequenceNr: processorId=" + processorId + " fromSequenceNr=" + fromSequenceNr + " highestSequenceNr=" + highestSequenceNr)
    }
    promise.success(highestSequenceNr)

    promise.future
  }

  override def asyncReplayMessages(processorId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(replayCallback: (PersistentRepr) => Unit): Future[Unit] = {

    val promise = Promise[Unit]()

    var rowsRead: Long = 0
    var maybeMoreData: Boolean = true
    var nextFromSequenceNr: Long = fromSequenceNr
    var numberOfReads: Long = 0

    try {
      val processorIdObject: ProcessorId = processorIdSplitter().split(processorId)
      while (maybeMoreData && (rowsRead < max)) {
        numberOfReads = numberOfReads + 1
        var maxRows: Long = maxRowsPrRead
        if ((rowsRead + maxRows) > max) {
          maxRows = max - rowsRead
        }
        if (log.isDebugEnabled) {
          log.debug("JdbcSyncWriteJournal doAsyncReplayMessages: processorId=" + processorId + " fromSequenceNr=" + fromSequenceNr + " toSequenceNr=" + toSequenceNr + " max=" + max + " - maxRows=" + maxRows + " rowsReadSoFar=" + rowsRead + " nextFromSequenceNr=" + nextFromSequenceNr)
        }
        val entries: List[JournalEntryDto] = repo().loadJournalEntries(processorIdObject, nextFromSequenceNr, toSequenceNr, maxRows)
        rowsRead = rowsRead + entries.size
        maybeMoreData = (entries.size == maxRows) && (maxRows > 0)
        entries.foreach {
          entry: JournalEntryDto =>
            val rawPersistentRepr: PersistentRepr = serialization.serializerFor(classOf[PersistentRepr]).fromBinary(entry.persistentRepr)
              .asInstanceOf[PersistentRepr]
              .update(deleted = entry.deleted, sequenceNr = entry.sequenceNr)

            val persistentRepr = if (!processorIdObject.isFull()) {
              // Must create a new modified one..
              val newPayload = JournalEntry(processorIdSplitter().split(rawPersistentRepr.persistenceId), rawPersistentRepr.payload.asInstanceOf[AnyRef])
              PersistentRepr(newPayload).update(sequenceNr = rawPersistentRepr.sequenceNr, persistenceId = rawPersistentRepr.processorId, sender = rawPersistentRepr.sender)
            } else {
              rawPersistentRepr
            }

            try {
              replayCallback.apply(persistentRepr)
            } catch {
              case e: Exception => throw new Exception("Error applying persistedMessage on replayCallback for " + persistentRepr, e)
            }

            nextFromSequenceNr = persistentRepr.sequenceNr + 1

        }
      }
      if (log.isDebugEnabled) {
        log.debug("JdbcSyncWriteJournal doAsyncReplayMessages: DONE - processorId=" + processorId + " fromSequenceNr=" + fromSequenceNr + " toSequenceNr=" + toSequenceNr + " max=" + max + " - numberOfReads=" + numberOfReads)
      }
      promise.success()
    }
    catch {
      case e: Exception => {
        val errorMessage: String = "Error replaying messages"
        log.error(e, errorMessage)
        promise.failure(new Exception(errorMessage, e))
      }
    }

    promise.future
  }
}