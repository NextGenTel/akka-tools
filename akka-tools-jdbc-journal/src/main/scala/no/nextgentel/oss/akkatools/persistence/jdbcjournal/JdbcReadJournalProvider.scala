package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.persistence.query._
import akka.persistence.query.scaladsl.{ReadJournal => ScalaReadJournal}
import akka.persistence.query.javadsl.{ReadJournal => JavaReadJournal}
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

  override def eventsByTag(tag: String, offset: Offset): javadsl.Source[EventEnvelope, NotUsed] =
    scalaJdbcReadJournal.eventsByTag(tag, offset).asJava

  override def currentEventsByTag(tag: String, offset: Offset): javadsl.Source[EventEnvelope, NotUsed] =
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
    Source.fromGraph(new JdbcEventsByPersistenceIdSource(system, configName, runtimeData, true, refreshInterval, persistenceIdParser.parse(persistenceId), fromSequenceNr, toSequenceNr))
  }

  override def currentEventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    Source.fromGraph(new JdbcEventsByPersistenceIdSource(system, configName, runtimeData, false, refreshInterval, persistenceIdParser.parse(persistenceId), fromSequenceNr, toSequenceNr))
  }


  private def parseTag(tagOrTags:String):PersistenceId = {
    val tags = tagOrTags.split("""\|""")

    if ( tags.size == 1) {
      PersistenceIdSingleTagOnly(tags(0))
    } else {
      PersistenceIdMultipleTags(tags.toList)
    }

  }


  override def eventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] = {
    val persistenceId = parseTag(tag)
    Source.fromGraph(new JdbcEventsByPersistenceIdSource(system, configName, runtimeData, true, refreshInterval, persistenceId, fromOffsetToSequence(offset), Long.MaxValue))
  }

  override def currentEventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] = {
    val persistenceId = parseTag(tag)
    Source.fromGraph(new JdbcEventsByPersistenceIdSource(system, configName, runtimeData, false, refreshInterval, persistenceId, fromOffsetToSequence(offset), Long.MaxValue))
  }

  private def fromOffsetToSequence(offset:Offset):Long = {
    offset match {
      case NoOffset        => 0
      case Sequence(value) => value
      case x               => throw new Exception(s"Offset of type ${x.getClass} is not supported")
    }
  }
}


