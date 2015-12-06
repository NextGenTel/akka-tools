package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, Props, ExtendedActorSystem}
import akka.persistence.PersistentRepr
import akka.persistence.query.{EventEnvelope, ReadJournalProvider}
import akka.persistence.query.scaladsl.{ReadJournal => ScalaReadJournal}
import akka.persistence.query.javadsl.{ReadJournal => JavaReadJournal}
import akka.serialization.SerializationExtension
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.scaladsl.Source
import com.typesafe.config.Config

import scala.concurrent.duration.{Duration, FiniteDuration}

class JdbcReadJournalProvider(system: ExtendedActorSystem, config: Config) extends ReadJournalProvider {

  lazy val jdbcReadJournal = new JdbcReadJournal(system, config)
  lazy val javaJdbcReadJournal = new JavaJdbcReadJournal(system, config)

  override def scaladslReadJournal(): ScalaReadJournal = jdbcReadJournal

  override def javadslReadJournal(): JavaReadJournal = javaJdbcReadJournal
}

object JdbcReadJournal {
  // Corresponds to the config section
  val identifier = "akka.persistence.query.jdbc-read-journal"
}

class JavaJdbcReadJournal(system: ExtendedActorSystem, config: Config) extends JavaReadJournal {

}

class JdbcReadJournal(system: ExtendedActorSystem, config: Config) extends ScalaReadJournal
with akka.persistence.query.scaladsl.EventsByPersistenceIdQuery {

  val refreshInterval: FiniteDuration = {
    val millis = config.getDuration("refresh-interval", TimeUnit.MILLISECONDS)
    FiniteDuration(millis, TimeUnit.MILLISECONDS)
  }

  override def eventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, Unit] = {
    val props = Props(new JdbcEventsByPersistenceIdActor(refreshInterval, persistenceId, fromSequenceNr, toSequenceNr))
    Source.actorPublisher[EventEnvelope](props)
      .mapMaterializedValue(_ ⇒ ())
  }
}


class JdbcEventsByPersistenceIdActor(refreshInterval: FiniteDuration, persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long)
  extends ActorPublisher[EventEnvelope] with ActorLogging {

  private case object Continue

  import JdbcJournal._

  val serializer = SerializationExtension.get(context.system).serializerFor(classOf[PersistentRepr])
  val processorIdObject: ProcessorId = processorIdSplitter().split(persistenceId)
  private var nextFromSequenceNr = fromSequenceNr
  var buf = Vector.empty[EventEnvelope]

  import context.dispatcher
  val continueTask = context.system.scheduler.schedule( refreshInterval, refreshInterval, self, Continue)

  override def postStop(): Unit = {
    continueTask.cancel()
  }

  def receive = {
    case _: Request | Continue ⇒
      query()
      deliverBuf()

    case Cancel ⇒
      context.stop(self)
  }


  def query(): Unit =
    if (buf.isEmpty) {
      try {

        log.debug(s"Reading entries for persistenceId=$persistenceId - nextFromSequenceNr=$nextFromSequenceNr, toSequenceNr=$toSequenceNr")
        val entries: List[JournalEntryDto] = repo().loadJournalEntries(processorIdObject, nextFromSequenceNr, toSequenceNr, maxRowsPrRead)
        buf = entries.map {
          entry: JournalEntryDto =>

            val persistentRepr = serializer.fromBinary(entry.persistentRepr).asInstanceOf[PersistentRepr]
            nextFromSequenceNr = entry.sequenceNr +1

            EventEnvelope(entry.sequenceNr, persistenceId, entry.sequenceNr, persistentRepr.payload)

        }.toVector

      } catch {
        case e: Exception ⇒
          onErrorThenStop(e)
      }
    }

  final def deliverBuf(): Unit =
    if (totalDemand > 0 && buf.nonEmpty) {
      if (totalDemand <= Int.MaxValue) {
        val (use, keep) = buf.splitAt(totalDemand.toInt)
        buf = keep
        use foreach onNext
      } else {
        buf foreach onNext
        buf = Vector.empty
      }
    }

}



