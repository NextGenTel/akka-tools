package no.nextgentel.oss.akkatools.persistence.jdbcjournal

object PersistenceIdType extends Enumeration {
  type PersistenceIdType = Value
  val FULL = Value
  val ONLY_TYPE = Value
}

import java.nio.charset.Charset
import javax.sql.DataSource

import PersistenceIdType._
import akka.actor.ActorLogging
import akka.persistence.AtomicWrite
import akka.persistence.PersistentRepr
import akka.persistence.SelectedSnapshot
import akka.persistence.SnapshotMetadata
import akka.persistence.SnapshotSelectionCriteria
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.snapshot.SnapshotStore
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
import no.nextgentel.oss.akkatools.cluster.ClusterNodeRepo
import no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializableButNotDeserializable
import org.sql2o.Sql2o
import org.sql2o.quirks.OracleQuirks

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try

case class PersistenceId(private val _typePath: String, private val _id: String, private val _persistenceIdType: PersistenceIdType = FULL) {

  def persistenceIdType() = _persistenceIdType

  def typePath() = _typePath

  def id(): String = {
    if (!isFull()) {
      throw new RuntimeException("Cannot get Id-part when persistenceIdType is not FULL")
    } else {
      _id
    }
  }

  def isFull() = FULL == _persistenceIdType

  override def toString: String = {
    "PersistenceId{" + "typePath='" + typePath + '\'' + (if (isFull) (", id='" + id + '\'') else "") + '}'
  }

}

trait PersistenceIdSplitter {
  def split(persistenceId: String): PersistenceId
  def splitChar():Option[Char]
}


// This is an impl not using the the split-functionality.
// It does no splitting at all
class PersistenceIdSplitterDefaultAkkaImpl extends PersistenceIdSplitter {
  def split(persistenceId: String): PersistenceId = {
    return new PersistenceId(persistenceId, "")
  }

  override def splitChar(): Option[Char] = None
}

object PersistenceIdSplitterLastSlashImpl {
  val WILDCARD: String = "*"
}

// Splits on the last slash
// Nice to use when persistenceId's looks like URLs
class PersistenceIdSplitterLastSlashImpl extends PersistenceIdSplitterLastSomethingImpl('/')


// Splits on the last occurrence of '_splitChar'
class PersistenceIdSplitterLastSomethingImpl(_splitChar:Char) extends PersistenceIdSplitter {
  def split(persistenceId: String): PersistenceId = {
    val i: Int = persistenceId.lastIndexOf(_splitChar)
    if (i < 0) {
      return new PersistenceId(persistenceId, "")
    } else {
      val typePath: String = persistenceId.substring(0, i + 1)
      val id: String = persistenceId.substring(i + 1, persistenceId.length)
      if (PersistenceIdSplitterLastSlashImpl.WILDCARD == id) {
        return PersistenceId(typePath, null, ONLY_TYPE)
      } else {
        return PersistenceId(typePath, id)
      }
    }
  }

  override def splitChar(): Option[Char] = Some(_splitChar)
}

object JdbcJournalConfig {

  // Java helper
  def create(dataSource: DataSource, schemaName: String, fatalErrorHandler: JdbcJournalErrorHandler) = JdbcJournalConfig(dataSource, Option(schemaName), fatalErrorHandler)
}

case class JdbcJournalConfig
(
  dataSource: DataSource,
  schemaName: Option[String],
  fatalErrorHandler: JdbcJournalErrorHandler, // The fatalErrorHandler is called when something bad has happend - like getting unique PK key errors - Which is probably a symptom of split brain
  persistenceIdSplitter: PersistenceIdSplitter = new PersistenceIdSplitterLastSlashImpl(),
  maxRowsPrRead: Int = JdbcJournal.DEFAULT_MAX_ROWS_PR_READ)

object JdbcJournal {
  val DEFAULT_MAX_ROWS_PR_READ = 1000
  private var _repo: Option[StorageRepo] = None
  private var _persistenceIdSplitter: Option[PersistenceIdSplitter] = None
  var maxRowsPrRead = DEFAULT_MAX_ROWS_PR_READ

  val jacksonJsonSerializer_className = "no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializer"

  // This must be called at startup, before any akka-persistence-code is executed
  def init(config: JdbcJournalConfig): Unit = {
    val errorHandler = new JdbcJournalDetectFatalOracleErrorHandler(config.fatalErrorHandler)
    _repo = Some(new StorageRepoImpl(new Sql2o(config.dataSource, new OracleQuirks()), config.schemaName, errorHandler))
    _persistenceIdSplitter = Some(config.persistenceIdSplitter)
  }

  def repo(): StorageRepo = _repo.getOrElse(throw new Exception("JdbcJournal not configured yet"))
  def clusterNodeRepo() = repo().asInstanceOf[ClusterNodeRepo]

  def persistenceIdSplitter(): PersistenceIdSplitter = _persistenceIdSplitter.getOrElse(throw new Exception("JdbcJournal not configured yet"))
}

class JdbcSnapshotStore extends SnapshotStore with ActorLogging {

  import JdbcJournal._

  val serialization = SerializationExtension.get(context.system)

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    if (log.isDebugEnabled) {
      log.debug("JdbcSnapshotStore - doLoadAsync: " + persistenceId + " criteria: " + criteria)
    }

    val promise = Promise[Option[SelectedSnapshot]]()
    try {
      repo().findSnapshotEntry(persistenceId, criteria.maxSequenceNr, criteria.maxTimestamp) match {
        case None =>
          if (log.isDebugEnabled) {
            log.debug("JdbcSnapshotStore - doLoadAsync: Not found - " + persistenceId + " criteria: " + criteria)
          }
          promise.success(None)
        case Some(e: SnapshotEntry) =>

          val snapshot = e.serializerId match {
            case Some(serializerId) => serialization.deserialize(e.snapshot, serializerId, e.manifest).get
            case None               => serialization.deserialize(e.snapshot, getClass().getClassLoader().loadClass(e.manifest))
          }

          val selectedSnapshot = SelectedSnapshot(
            new SnapshotMetadata(
              e.persistenceId,
              e.sequenceNr,
              e.timestamp),
            snapshot)

          promise.success(Some(selectedSnapshot))
      }
    } catch {
      case e: Exception =>
        val errorMessage = "Error loading snapshot " + persistenceId + " - " + criteria
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

      val serializer = serialization.serializerFor(snapshot.getClass)
      val bytes = serializer.toBinary(snapshot.asInstanceOf[AnyRef])
      val manifest:String = serializer match {
        case s:SerializerWithStringManifest => s.manifest(snapshot.asInstanceOf[AnyRef])
        case _                              => snapshot.getClass.getName
      }


      repo().writeSnapshot(
        new SnapshotEntry(
          metadata.persistenceId,
          metadata.sequenceNr,
          metadata.timestamp,
          bytes,
          manifest,
          Some(serializer.identifier)))
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

case class JournalEntry(persistenceId: PersistenceId, payload: AnyRef) {
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
              JournalEntryDto(persistenceIdSplitter().split(p.persistenceId), p.sequenceNr, serialization.serialize(p).get, payloadJson.getOrElse(null))
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
        log.debug("JdbcAsyncWriteJournal doDeleteMessagesTo: persistenceId: " + persistenceId + " toSequenceNr=" + toSequenceNr)
      }

      repo().deleteJournalEntryTo(persistenceIdSplitter().split(persistenceId), toSequenceNr)
    })
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    val promise = Promise[Long]()

    val highestSequenceNr = repo().findHighestSequenceNr(persistenceIdSplitter().split(persistenceId), fromSequenceNr)
    if (log.isDebugEnabled) {
      log.debug("JdbcAsyncWriteJournal doAsyncReadHighestSequenceNr: persistenceId=" + persistenceId + " fromSequenceNr=" + fromSequenceNr + " highestSequenceNr=" + highestSequenceNr)
    }
    promise.success(highestSequenceNr)

    promise.future
  }

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(replayCallback: (PersistentRepr) => Unit): Future[Unit] = {

    val promise = Promise[Unit]()

    var rowsRead: Long = 0
    var maybeMoreData: Boolean = true
    var nextFromSequenceNr: Long = fromSequenceNr
    var numberOfReads: Long = 0

    try {
      val persistenceIdObject: PersistenceId = persistenceIdSplitter().split(persistenceId)
      while (maybeMoreData && (rowsRead < max)) {
        numberOfReads = numberOfReads + 1
        var maxRows: Long = maxRowsPrRead
        if ((rowsRead + maxRows) > max) {
          maxRows = max - rowsRead
        }
        if (log.isDebugEnabled) {
          log.debug("JdbcAsyncWriteJournal doAsyncReplayMessages: persistenceId=" + persistenceId + " fromSequenceNr=" + fromSequenceNr + " toSequenceNr=" + toSequenceNr + " max=" + max + " - maxRows=" + maxRows + " rowsReadSoFar=" + rowsRead + " nextFromSequenceNr=" + nextFromSequenceNr)
        }
        val entries: List[JournalEntryDto] = repo().loadJournalEntries(persistenceIdObject, nextFromSequenceNr, toSequenceNr, maxRows)
        rowsRead = rowsRead + entries.size
        maybeMoreData = (entries.size == maxRows) && (maxRows > 0)
        entries.foreach {
          entry: JournalEntryDto =>

            val rawPersistentRepr: PersistentRepr = serialization.serializerFor(classOf[PersistentRepr]).fromBinary(entry.persistentRepr)
              .asInstanceOf[PersistentRepr]
              .update(sequenceNr = entry.sequenceNr)

            val persistentRepr = if (!persistenceIdObject.isFull()) {
              // Must create a new modified one..
              val newPayload = JournalEntry(persistenceIdSplitter().split(rawPersistentRepr.persistenceId), rawPersistentRepr.payload.asInstanceOf[AnyRef])
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
        log.debug("JdbcAsyncWriteJournal doAsyncReplayMessages: DONE - persistenceId=" + persistenceId + " fromSequenceNr=" + fromSequenceNr + " toSequenceNr=" + toSequenceNr + " max=" + max + " - numberOfReads=" + numberOfReads)
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