package no.nextgentel.oss.akkatools.persistence.jdbcjournal


import akka.actor.{Props, ActorLogging, Actor, ActorSystem}
import akka.persistence.PersistentActor
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{TestProbe, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfter, Matchers, BeforeAndAfterAll, FunSuiteLike}
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration


class JdbcReadJournalTest(_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with BeforeAndAfter with BeforeAndAfterAll with Matchers {

  def this() = this(ActorSystem("JdbcReadJournalTest", ConfigFactory.load("application-test.conf")))

  val log = LoggerFactory.getLogger(getClass)

  val errorHandler = new JdbcJournalErrorHandler {
    override def onError(e: Exception): Unit = log.error("JdbcJournalErrorHandler.onError", e)
  }


  val readJournal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.identifier)

  val halfRefreshIntervalInMills:Long = readJournal.refreshInterval.toMillis/2

  var nextIdValue = System.currentTimeMillis()

  before {
    // Need a new datasource for each test to make sure the global db counter/sequenceNo starts on 0
    val dataSource = DataSourceUtil.createDataSource("MyJournalSpec-"+System.currentTimeMillis(), "akka-tools-jdbc-journal-liquibase.sql")
    JdbcJournal.init(JdbcJournalConfig(dataSource, None, errorHandler, new PersistenceIdSplitterLastSomethingImpl('-')))
  }


  override protected def afterAll(): Unit = {
    val f = system.terminate()
    Await.ready(f, Duration("2s"))
  }

  def uniquePersistenceId(tag:String):String = {
    val id = s"$tag-$nextIdValue"
    nextIdValue = nextIdValue + 1
    id
  }

  test("EventsByPersistenceIdQuery") {

    val persistenceId = uniquePersistenceId("pa")
    val pa = system.actorOf(Props(new TestPersistentActor(persistenceId)))
    val paOther = system.actorOf(Props(new TestPersistentActor(uniquePersistenceId("pa"))))

    val source: Source[EventEnvelope, Unit] =
      readJournal.eventsByPersistenceId(persistenceId, 0, Long.MaxValue)

    val streamResult = TestProbe()

    // materialize stream, consuming events
    implicit val mat = ActorMaterializer()
    source.runForeach {
      event =>
        println("Stream received Event: " + event)
        streamResult.ref ! event
    }

    Thread.sleep(halfRefreshIntervalInMills)

    pa ! TestCmd("a")
    paOther ! TestCmd("other-a")

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(1, persistenceId, 1, TestEvent("a")))

    pa ! TestCmd("b")
    paOther ! TestCmd("other-b")
    pa ! TestCmd("c")
    paOther ! TestCmd("other-c")


    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(2, persistenceId, 2, TestEvent("b")),
      EventEnvelope(3, persistenceId, 3, TestEvent("c")))

    pa ! TestCmd("d")

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle
    streamResult.expectMsgAllOf(
      EventEnvelope(4, persistenceId, 4, TestEvent("d")))

  }

  test("currentEventsByPersistenceId") {
    val persistenceId = uniquePersistenceId("pa")
    val pa = system.actorOf(Props(new TestPersistentActor(persistenceId)))
    val paOther = system.actorOf(Props(new TestPersistentActor(uniquePersistenceId("pa"))))

    pa ! TestCmd("a")
    paOther ! TestCmd("other-a")
    pa ! TestCmd("b")
    paOther ! TestCmd("other-b")
    pa ! TestCmd("c")
    paOther ! TestCmd("other-c")

    Thread.sleep(halfRefreshIntervalInMills) // Skip to next read cycle

    val source: Source[EventEnvelope, Unit] =
      readJournal.currentEventsByPersistenceId(persistenceId, 0, Long.MaxValue)


    val streamResult = TestProbe()

    // materialize stream, consuming events
    implicit val mat = ActorMaterializer()
    source.runForeach {
      event =>
        println("Stream received Event: " + event)
        streamResult.ref ! event
    }

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(1, persistenceId, 1, TestEvent("a")),
      EventEnvelope(2, persistenceId, 2, TestEvent("b")),
      EventEnvelope(3, persistenceId, 3, TestEvent("c")))

    pa ! TestCmd("x")
    paOther ! TestCmd("other-x")

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectNoMsg() // The stream should have stopped

  }

  test("eventsByTag") {
    val tag = "pb"
    val id1 = uniquePersistenceId(tag)
    val id2 = uniquePersistenceId(tag)
    val pa1 = system.actorOf(Props(new TestPersistentActor(id1)))
    val pa2 = system.actorOf(Props(new TestPersistentActor(id2)))

    val source: Source[EventEnvelope, Unit] =
      readJournal.eventsByTag(tag, 0)

    val streamResult = TestProbe()

    // materialize stream, consuming events
    implicit val mat = ActorMaterializer()
    source.runForeach {
      event =>
        println("Stream received Event: " + event)
        streamResult.ref ! event
    }

    Thread.sleep(halfRefreshIntervalInMills)

    pa1 ! TestCmd("a1")
    Thread.sleep(50)
    pa2 ! TestCmd("a2")
    Thread.sleep(50)

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(1, id1, 1, TestEvent("a1")),
      EventEnvelope(2, id2, 2, TestEvent("a2")))

    pa1 ! TestCmd("b1")
    Thread.sleep(50)
    pa2 ! TestCmd("b2")
    Thread.sleep(50)
    pa1 ! TestCmd("c1")
    Thread.sleep(50)
    pa2 ! TestCmd("c2")


    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(3, id1, 3, TestEvent("b1")),
      EventEnvelope(4, id2, 4, TestEvent("b2")),
      EventEnvelope(5, id1, 5, TestEvent("c1")),
      EventEnvelope(6, id2, 6, TestEvent("c2")))

    pa1 ! TestCmd("d1")
    Thread.sleep(50)
    pa2 ! TestCmd("d2")

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle
    streamResult.expectMsgAllOf(
      EventEnvelope(7, id1, 7, TestEvent("d1")),
      EventEnvelope(8, id2, 8, TestEvent("d2")))
  }

  test("currentEventsByTag") {
    val tag = "pc"
    val id1 = uniquePersistenceId(tag)
    val id2 = uniquePersistenceId(tag)
    val pa1 = system.actorOf(Props(new TestPersistentActor(id1)))
    val pa2 = system.actorOf(Props(new TestPersistentActor(id2)))

    Thread.sleep(halfRefreshIntervalInMills)

    pa1 ! TestCmd("a1")
    Thread.sleep(50)
    pa2 ! TestCmd("a2")
    Thread.sleep(50)
    pa1 ! TestCmd("b1")
    Thread.sleep(50)
    pa2 ! TestCmd("b2")

    Thread.sleep(halfRefreshIntervalInMills)

    val source: Source[EventEnvelope, Unit] =
      readJournal.currentEventsByTag(tag, 0)

    val streamResult = TestProbe()

    // materialize stream, consuming events
    implicit val mat = ActorMaterializer()
    source.runForeach {
      event =>
        println("Stream received Event: " + event)
        streamResult.ref ! event
    }

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(1, id1, 1, TestEvent("a1")),
      EventEnvelope(2, id2, 2, TestEvent("a2")),
      EventEnvelope(3, id1, 3, TestEvent("b1")),
      EventEnvelope(4, id2, 4, TestEvent("b2")))

    pa1 ! TestCmd("c1")
    Thread.sleep(50)
    pa2 ! TestCmd("c2")

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectNoMsg()// The stream should have stopped
  }

}

case class TestCmd(v:String)

case class TestEvent(v:String)

class TestPersistentActor(val persistenceId:String) extends Actor with PersistentActor with ActorLogging {
  override def receiveRecover: Receive = {
    case x:Any => log.debug(s"receiveRecover: $x")
  }

  override def receiveCommand: Receive = {
    case TestCmd(v) =>
      val event = TestEvent(v)
      persist( event) {
        e =>
          log.info(s"Persisted $event")
      }
  }

}