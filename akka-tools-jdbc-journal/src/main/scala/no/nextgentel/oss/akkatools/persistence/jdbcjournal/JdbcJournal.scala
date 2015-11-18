package no.nextgentel.oss.akkatools.persistence.jdbcjournal

object ProcessorIdType extends Enumeration {
  type ProcessorIdType = Value
  val FULL = Value
  val ONLY_TYPE = Value
}

import java.nio.charset.Charset
import javax.sql.DataSource

import ProcessorIdType._
import akka.actor.ActorLogging
import akka.persistence.AtomicWrite
import akka.persistence.PersistentRepr
import akka.persistence.SelectedSnapshot
import akka.persistence.SnapshotMetadata
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.snapshot.SnapshotStore
import akka.serialization.SerializationExtension
import no.nextgentel.oss.akkatools.cluster.ClusterNodeRepo
import no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializableButNotDeserializable
import org.sql2o.Sql2o
import org.sql2o.quirks.OracleQuirks

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try

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

          val selectedSnapshot = SelectedSnapshot(
            new SnapshotMetadata(
              e.processorId,
              e.sequenceNr,
              e.timestamp),
            snapshot)

          promise.success(Some(selectedSnapshot))
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
      promise.success(Unit)
    } catch {
      case e: Exception => {
        val errorMessage: String = "Error storing snapshot"
        log.error(e, errorMessage)
        promise.failure(new Exception(errorMessage, e))
      }
    }

    promise.future
  }



  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
    Future.fromTry(Try {
      if (log.isDebugEnabled) {
        log.debug("JdbcSnapshotStore - doDelete: " + metadata)
      }

      repo().deleteSnapshot(metadata.persistenceId, metadata.sequenceNr, metadata.timestamp)
    })
  }

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    Future.fromTry(Try {
      if (log.isDebugEnabled) {
        log.debug("JdbcSnapshotStore - doDelete: " + persistenceId + " criteria " + criteria)
      }

      repo().deleteSnapshotsMatching(persistenceId, criteria.maxSequenceNr, criteria.maxTimestamp)
    })
  }
}

case class JournalEntry(processorId: ProcessorId, payload: AnyRef) {
  def payloadAs[T](): T = payload.asInstanceOf[T]
}


// Need JacksonJsonSerializableButNotDeserializable since we're using JacksonJsonSerializer to generate
// read-only-json with type-name-info, and if it has serializationVerification turned on,
// This class will fail since it does not have type-info..
case class JsonObjectHolder(t:String, o:AnyRef) extends JacksonJsonSerializableButNotDeserializable

class JdbcAsyncWriteJournal extends AsyncWriteJournal with ActorLogging {

  import JdbcJournal._

  val serialization = SerializationExtension.get(context.system)


  override def asyncWriteMessages(messages: Seq[AtomicWrite]): Future[Seq[Try[Unit]]] = {

    if (log.isDebugEnabled) {
      log.debug("JdbcAsyncWriteJournal doWriteMessages messages: " + messages.size)
    }

    val promise = Promise[Seq[Try[Unit]]]()
    promise.success(messages.map {
      atomicWrite =>
        Try {
          val dtoList: Seq[JournalEntryDto] = atomicWrite.payload.map {
            p =>
              if (log.isDebugEnabled) {
                log.debug("JdbcAsyncWriteJournal doWriteMessages: persistentRepr: " + p)
              }

              val payloadJson = tryToExtractPayloadAsJson(p)
              JournalEntryDto(processorIdSplitter().split(p.persistenceId), p.sequenceNr, serialization.serialize(p).get, payloadJson.getOrElse(null))
          }

          try {
            repo().insertPersistentReprList(dtoList)
          } catch {
            case e:Exception =>
              log.error(e, s"Error while persisting ${dtoList.size} PersistentReprs")
              throw e
          }
        }
    })
    promise.future
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


  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] = {
    Future.fromTry(Try {
      if (log.isDebugEnabled) {
        log.debug("JdbcAsyncWriteJournal doDeleteMessagesTo: processorId: " + persistenceId + " toSequenceNr=" + toSequenceNr)
      }

      repo().deleteJournalEntryTo(processorIdSplitter().split(persistenceId), toSequenceNr)
    })
  }

  override def asyncReadHighestSequenceNr(processorId: String, fromSequenceNr: Long): Future[Long] = {
    val promise = Promise[Long]()

    val highestSequenceNr = repo().findHighestSequenceNr(processorIdSplitter().split(processorId), fromSequenceNr)
    if (log.isDebugEnabled) {
      log.debug("JdbcAsyncWriteJournal doAsyncReadHighestSequenceNr: processorId=" + processorId + " fromSequenceNr=" + fromSequenceNr + " highestSequenceNr=" + highestSequenceNr)
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
          log.debug("JdbcAsyncWriteJournal doAsyncReplayMessages: processorId=" + processorId + " fromSequenceNr=" + fromSequenceNr + " toSequenceNr=" + toSequenceNr + " max=" + max + " - maxRows=" + maxRows + " rowsReadSoFar=" + rowsRead + " nextFromSequenceNr=" + nextFromSequenceNr)
        }
        val entries: List[JournalEntryDto] = repo().loadJournalEntries(processorIdObject, nextFromSequenceNr, toSequenceNr, maxRows)
        rowsRead = rowsRead + entries.size
        maybeMoreData = (entries.size == maxRows) && (maxRows > 0)
        entries.foreach {
          entry: JournalEntryDto =>
            val rawPersistentRepr: PersistentRepr = serialization.serializerFor(classOf[PersistentRepr]).fromBinary(entry.persistentRepr)
              .asInstanceOf[PersistentRepr]
              .update(sequenceNr = entry.sequenceNr)

            val persistentRepr = if (!processorIdObject.isFull()) {
              // Must create a new modified one..
              val newPayload = JournalEntry(processorIdSplitter().split(rawPersistentRepr.persistenceId), rawPersistentRepr.payload.asInstanceOf[AnyRef])
              PersistentRepr(newPayload).update(sequenceNr = rawPersistentRepr.sequenceNr, persistenceId = rawPersistentRepr.persistenceId, sender = rawPersistentRepr.sender)
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
        log.debug("JdbcAsyncWriteJournal doAsyncReplayMessages: DONE - processorId=" + processorId + " fromSequenceNr=" + fromSequenceNr + " toSequenceNr=" + toSequenceNr + " max=" + max + " - numberOfReads=" + numberOfReads)
      }
      promise.success(Unit)
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