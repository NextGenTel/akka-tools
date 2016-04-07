package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.nio.charset.Charset

import akka.actor.ActorLogging
import akka.persistence.{AtomicWrite, PersistentRepr}
import akka.persistence.journal.AsyncWriteJournal
import akka.serialization.SerializationExtension
import com.typesafe.config.Config
import no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializableButNotDeserializable

import scala.collection.immutable.Seq
import scala.concurrent.{Future, Promise}
import scala.util.Try

class JdbcAsyncWriteJournal(val config: Config) extends AsyncWriteJournal with ActorLogging with JdbcJournalExtractRuntimeData {

  import JdbcJournal._


  val persistenceIdSplitter = runtimeData.persistenceIdSplitter
  val repo = runtimeData.repo
  val maxRowsPrRead = runtimeData.maxRowsPrRead

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
              JournalEntryDto(persistenceIdSplitter.split(p.persistenceId), p.sequenceNr, serialization.serialize(p).get, payloadJson.getOrElse(null))
          }

          try {
            repo.insertPersistentReprList(dtoList)
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

      repo.deleteJournalEntryTo(persistenceIdSplitter.split(persistenceId), toSequenceNr)
    })
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    val promise = Promise[Long]()

    val highestSequenceNr = repo.findHighestSequenceNr(persistenceIdSplitter.split(persistenceId), fromSequenceNr)
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
      val persistenceIdObject: PersistenceId = persistenceIdSplitter.split(persistenceId)
      while (maybeMoreData && (rowsRead < max)) {
        numberOfReads = numberOfReads + 1
        var maxRows: Long = maxRowsPrRead
        if ((rowsRead + maxRows) > max) {
          maxRows = max - rowsRead
        }
        if (log.isDebugEnabled) {
          log.debug("JdbcAsyncWriteJournal doAsyncReplayMessages: persistenceId=" + persistenceId + " fromSequenceNr=" + fromSequenceNr + " toSequenceNr=" + toSequenceNr + " max=" + max + " - maxRows=" + maxRows + " rowsReadSoFar=" + rowsRead + " nextFromSequenceNr=" + nextFromSequenceNr)
        }
        val entries: List[JournalEntryDto] = repo.loadJournalEntries(persistenceIdObject, nextFromSequenceNr, toSequenceNr, maxRows)
        rowsRead = rowsRead + entries.size
        maybeMoreData = (entries.size == maxRows) && (maxRows > 0)
        entries.foreach {
          entry: JournalEntryDto =>

            val rawPersistentRepr: PersistentRepr = serialization.serializerFor(classOf[PersistentRepr]).fromBinary(entry.persistentRepr)
              .asInstanceOf[PersistentRepr]
              .update(sequenceNr = entry.sequenceNr)

            val persistentRepr = if (!persistenceIdObject.isFull()) {
              // Must create a new modified one..
              val newPayload = JournalEntry(persistenceIdSplitter.split(rawPersistentRepr.persistenceId), rawPersistentRepr.payload.asInstanceOf[AnyRef])
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


// Need JacksonJsonSerializableButNotDeserializable since we're using JacksonJsonSerializer to generate
// read-only-json with type-name-info, and if it has serializationVerification turned on,
// This class will fail since it does not have type-info..
case class JsonObjectHolder(t:String, o:AnyRef) extends JacksonJsonSerializableButNotDeserializable
