package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.{ActorLogging, ExtendedActorSystem, Props}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.persistence.PersistentRepr
import akka.persistence.query.{EventEnvelope, ReadJournalProvider}
import akka.persistence.query.scaladsl.{ReadJournal => ScalaReadJournal}
import akka.persistence.query.javadsl.{ReadJournal => JavaReadJournal}
import akka.serialization.SerializationExtension
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{Cancel, Request}
import akka.stream.javadsl
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

class JavaJdbcReadJournal(system: ExtendedActorSystem, config: Config) extends JavaReadJournal
with akka.persistence.query.javadsl.EventsByPersistenceIdQuery
with akka.persistence.query.javadsl.CurrentEventsByPersistenceIdQuery
with akka.persistence.query.javadsl.EventsByTagQuery
with akka.persistence.query.javadsl.CurrentEventsByTagQuery {


  val scalaJdbcReadJournal = new JdbcReadJournal(system, config)

  override def eventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): javadsl.Source[EventEnvelope, NotUsed] =
    scalaJdbcReadJournal.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).asJava

  override def currentEventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): javadsl.Source[EventEnvelope, NotUsed] =
    scalaJdbcReadJournal.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).asJava

  override def eventsByTag(tag: String, offset: Long): javadsl.Source[EventEnvelope, NotUsed] =
    scalaJdbcReadJournal.eventsByTag(tag, offset).asJava

  override def currentEventsByTag(tag: String, offset: Long): javadsl.Source[EventEnvelope, NotUsed] =
    scalaJdbcReadJournal.currentEventsByTag(tag, offset).asJava
}

class JdbcReadJournal(system: ExtendedActorSystem, val config: Config) extends ScalaReadJournal
with akka.persistence.query.scaladsl.EventsByPersistenceIdQuery
with akka.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery
with akka.persistence.query.scaladsl.EventsByTagQuery
with akka.persistence.query.scaladsl.CurrentEventsByTagQuery
with JdbcJournalRuntimeDataExtractor {

  val persistenceIdParser = runtimeData.persistenceIdParser


  val refreshInterval: FiniteDuration = {
    val millis = config.getDuration("refresh-interval", TimeUnit.MILLISECONDS)
    FiniteDuration(millis, TimeUnit.MILLISECONDS)
  }

  override def eventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    val props = Props(new JdbcEventsByPersistenceIdActor(configName, runtimeData, true, refreshInterval, persistenceIdParser.parse(persistenceId), fromSequenceNr, toSequenceNr))
    Source.actorPublisher[EventEnvelope](props).mapMaterializedValue(_ ⇒ NotUsed)
  }

  override def currentEventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    val props = Props(new JdbcEventsByPersistenceIdActor(configName, runtimeData, false, refreshInterval, persistenceIdParser.parse(persistenceId), fromSequenceNr, toSequenceNr))
    Source.actorPublisher[EventEnvelope](props).mapMaterializedValue(_ ⇒ NotUsed)
  }


  private def parseTag(tagOrTags:String):PersistenceId = {
    val tags = tagOrTags.split("""\|""")

    if ( tags.size == 1) {
      PersistenceIdSingleTagOnly(tags(0))
    } else {
      PersistenceIdMultipleTags(tags.toList)
    }

  }


  override def eventsByTag(tag: String, offset: Long): Source[EventEnvelope, NotUsed] = {
    val persistenceId = parseTag(tag)
    val props = Props(new JdbcEventsByPersistenceIdActor(configName, runtimeData, true, refreshInterval, persistenceId, offset, Long.MaxValue))
    Source.actorPublisher[EventEnvelope](props).mapMaterializedValue(_ ⇒ NotUsed)
  }

  override def currentEventsByTag(tag: String, offset: Long): Source[EventEnvelope, NotUsed] = {
    val persistenceId = parseTag(tag)
    val props = Props(new JdbcEventsByPersistenceIdActor(configName, runtimeData, false, refreshInterval, persistenceId, offset, Long.MaxValue))
    Source.actorPublisher[EventEnvelope](props).mapMaterializedValue(_ ⇒ NotUsed)
  }
}

private case object Continue

class JdbcEventsByPersistenceIdActor(configName:String, runtimeData:JdbcJournalRuntimeData, live:Boolean, refreshInterval: FiniteDuration, persistenceId: PersistenceId, fromSequenceNr: Long, toSequenceNr: Long)
  extends ActorPublisher[EventEnvelope] with ActorLogging {

  val serializer = SerializationExtension.get(context.system).serializerFor(classOf[PersistentRepr])
  private var nextFromSequenceNr = fromSequenceNr
  var buf = Vector.empty[EventEnvelope]

  import context.dispatcher
  val continueTask = context.system.scheduler.schedule( refreshInterval, refreshInterval, self, Continue)

  // Start listening for EntryWrittenToTag-messages which is publish each time an event is written for
  // a specific tag - maybe the one we're tracking
  val pubsubMediator = DistributedPubSub(context.system).mediator

  if ( live ) {

    val subscriptionTopics:List[String] = persistenceId match {
      case p:PersistenceIdSingle =>
        List(EntryWrittenToTag.topic(configName, p.tag))
      case p:PersistenceIdTagsOnly =>
        p.tags.map {
          tag =>
            EntryWrittenToTag.topic(configName, tag)
        }

    }

    subscriptionTopics.foreach {
      subscriptionTopic =>
        pubsubMediator ! Subscribe( subscriptionTopic, self)
    }


  }

  override def postStop(): Unit = {
    continueTask.cancel()
  }

  def receive = {
    case entryWrittenToTag: EntryWrittenToTag =>
      persistenceId match {
        case p:PersistenceIdTagsOnly =>
          // We're tracking a tags
          doWork()

        case p:PersistenceIdSingle =>
          // we're tracking single id
          if( runtimeData.persistenceIdParser.parse(entryWrittenToTag.persistenceId) == persistenceId) {
            // This means one of our events have been written
            doWork()
          }
      }
      if ( persistenceId.isInstanceOf[PersistenceIdSingleTagOnly] ) {
        // We're tracking a tag
        doWork()
      } else if(entryWrittenToTag.persistenceId == persistenceId) {
        // This means one of our events have been written
        doWork()
      }


    case _: Request | Continue ⇒
      doWork()

    case Cancel ⇒
      context.stop(self)
  }

  def doWork(): Unit = {
    query()
    deliverBuf()
  }


  def query(): Unit =
    if (buf.isEmpty) {
      try {

        log.debug(s"Reading entries for persistenceId=$persistenceId - nextFromSequenceNr=$nextFromSequenceNr, toSequenceNr=$toSequenceNr")
        val entries: List[JournalEntryDto] = runtimeData.repo.loadJournalEntries(persistenceId, nextFromSequenceNr, toSequenceNr, runtimeData.maxRowsPrRead)
        buf = entries.map {
          entry: JournalEntryDto =>

            val persistentRepr = serializer.fromBinary(entry.persistentRepr).asInstanceOf[PersistentRepr]
            nextFromSequenceNr = entry.sequenceNr +1

            // TODO: Need to add test for this
            val event:AnyRef = persistentRepr.payload match {
              case q:EventWithInjectableTimestamp => q.cloneWithInjectedTimestamp(entry.timestamp)
              case x:AnyRef => x
            }

            EventEnvelope(entry.sequenceNr, persistentRepr.persistenceId, entry.sequenceNr, event)

        }.toVector

        if ( !live && buf.isEmpty) {
          log.debug(s"Stopping none-live stream for persistenceId=$persistenceId")
          onCompleteThenStop()
        }


      } catch {
        case e: Exception ⇒
          log.error(e, "JdbcEventsByPersistenceIdActor stopped for persistenceId=$persistenceId")
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



