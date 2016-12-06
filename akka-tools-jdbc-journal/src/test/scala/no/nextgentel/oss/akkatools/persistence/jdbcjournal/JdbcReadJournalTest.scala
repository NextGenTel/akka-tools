package no.nextgentel.oss.akkatools.persistence.jdbcjournal


import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.persistence.PersistentActor
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{TestKit, TestKitBase, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest._
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration}
import akka.pattern.ask
import akka.util.Timeout


// Need separate actorSystems for each test to prevent them from interacting with each other


abstract class JdbcReadJournalTestBase(configName:String) extends FunSuite with TestKitBase with BeforeAndAfter with BeforeAndAfterAll with Matchers {

  implicit lazy val system = ActorSystem(getClass.getSimpleName, ConfigFactory.parseString(
    s"""
       |akka.persistence.query.jdbc-read-journal.configName = $configName
       |jdbc-journal.configName = $configName
       |jdbc-snapshot-store.configName = $configName
     """.stripMargin).withFallback(ConfigFactory.load("application-test.conf")))

  val log = LoggerFactory.getLogger(getClass)

  implicit val _timeout = Timeout(5, TimeUnit.SECONDS)
  val timeout = Duration(5, TimeUnit.SECONDS)

  val errorHandler = new JdbcJournalErrorHandler {
    override def onError(e: Exception): Unit = log.error("JdbcJournalErrorHandler.onError", e)
  }

  lazy val readJournal = {
    JdbcJournalConfig.setConfig(configName, JdbcJournalConfig(DataSourceUtil.createDataSource("JdbcReadJournalTest"), errorHandler, StorageRepoConfig(), new PersistenceIdParserImpl('-')))
    PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.identifier)
  }

  lazy val halfRefreshIntervalInMills: Long = readJournal.refreshInterval.toMillis / 2

  var nextIdValue = System.currentTimeMillis()

  override protected def afterAll(): Unit = {
    val f = system.terminate()
    Await.ready(f, Duration("2s"))
  }

  def uniquePersistenceId(tag: String): String = {
    val id = s"$tag-$nextIdValue"
    nextIdValue = nextIdValue + 1
    id
  }

}

class JdbcReadJournalTest1 extends JdbcReadJournalTestBase("JdbcReadJournalTest1") {
  test("EventsByPersistenceIdQuery") {

    val persistenceId = uniquePersistenceId("pa")
    val source: Source[EventEnvelope, NotUsed] =
      readJournal.eventsByPersistenceId(persistenceId, 0, Long.MaxValue)


    val pa = system.actorOf(Props(new TestPersistentActor(persistenceId)))
    val paOther = system.actorOf(Props(new TestPersistentActor(uniquePersistenceId("pa"))))


    val streamResult = TestProbe()

    // materialize stream, consuming events
    implicit val mat = ActorMaterializer()
    source.runForeach {
      event =>
        println("Stream received Event: " + event)
        streamResult.ref ! event
    }

    Thread.sleep(halfRefreshIntervalInMills)

    Await.ready(ask(pa, TestCmd("a")), timeout)
    Await.ready(ask(paOther, TestCmd("other-a")), timeout)

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(1, persistenceId, 1, TestEvent("a")))

    Await.ready(ask(pa, TestCmd("b")), timeout)
    Await.ready(ask(paOther, TestCmd("other-b")), timeout)
    Await.ready(ask(pa, TestCmd("c")), timeout)
    Await.ready(ask(paOther, TestCmd("other-c")), timeout)


    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(2, persistenceId, 2, TestEvent("b")),
      EventEnvelope(3, persistenceId, 3, TestEvent("c")))

    Await.ready(ask(pa, TestCmd("d")), timeout)

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle
    streamResult.expectMsgAllOf(
      EventEnvelope(4, persistenceId, 4, TestEvent("d")))
  }
}

class JdbcReadJournalTest2 extends JdbcReadJournalTestBase("JdbcReadJournalTest2") {
  test("currentEventsByPersistenceId") {
    val persistenceId = uniquePersistenceId("pa1")

    val source: Source[EventEnvelope, NotUsed] =
      readJournal.currentEventsByPersistenceId(persistenceId, 0, Long.MaxValue)


    val pa = system.actorOf(Props(new TestPersistentActor(persistenceId)))
    val paOther = system.actorOf(Props(new TestPersistentActor(uniquePersistenceId("pa1"))))

    Await.ready(ask(pa, TestCmd("a")), timeout)
    Await.ready(ask(paOther, TestCmd("other-a")), timeout)
    Await.ready(ask(pa, TestCmd("b")), timeout)
    Await.ready(ask(paOther, TestCmd("other-b")), timeout)
    Await.ready(ask(pa, TestCmd("c")), timeout)
    Await.ready(ask(paOther, TestCmd("other-c")), timeout)

    Thread.sleep(halfRefreshIntervalInMills)
    // Skip to next read cycle

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

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    Await.ready(ask(pa, TestCmd("x")), timeout)
    Await.ready(ask(paOther, TestCmd("other-x")), timeout)

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectNoMsg(FiniteDuration(halfRefreshIntervalInMills, TimeUnit.MILLISECONDS)) // The stream should have stopped
  }
}

class JdbcReadJournalTest3 extends JdbcReadJournalTestBase("JdbcReadJournalTest3") {
  test("eventsByTag") {
    val tag = "pb"

    val source: Source[EventEnvelope, NotUsed] =
      readJournal.eventsByTag(tag, 0)


    val id1 = uniquePersistenceId(tag)
    val id2 = uniquePersistenceId(tag)
    val pa1 = system.actorOf(Props(new TestPersistentActor(id1)))
    val pa2 = system.actorOf(Props(new TestPersistentActor(id2)))

    val streamResult = TestProbe()

    // materialize stream, consuming events
    implicit val mat = ActorMaterializer()
    source.runForeach {
      event =>
        println("Stream received Event: " + event)
        streamResult.ref ! event
    }

    Thread.sleep(halfRefreshIntervalInMills)

    Await.ready(ask(pa1, TestCmd("a1")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa2, TestCmd("a2")), timeout)
    Thread.sleep(100)

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(1, id1, 1, TestEvent("a1")),
      EventEnvelope(2, id2, 2, TestEvent("a2")))

    Await.ready(ask(pa1, TestCmd("b1")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa2, TestCmd("b2")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa1, TestCmd("c1")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa2, TestCmd("c2")), timeout)


    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(3, id1, 3, TestEvent("b1")),
      EventEnvelope(4, id2, 4, TestEvent("b2")),
      EventEnvelope(5, id1, 5, TestEvent("c1")),
      EventEnvelope(6, id2, 6, TestEvent("c2")))

    Await.ready(ask(pa1, TestCmd("d1")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa2, TestCmd("d2")), timeout)

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle
    streamResult.expectMsgAllOf(
      EventEnvelope(7, id1, 7, TestEvent("d1")),
      EventEnvelope(8, id2, 8, TestEvent("d2")))
  }
}

class JdbcReadJournalTest4 extends JdbcReadJournalTestBase("JdbcReadJournalTest4") {
  test("currentEventsByTag") {
    val tag = "pc"

    val source: Source[EventEnvelope, NotUsed] =
      readJournal.currentEventsByTag(tag, 0)


    val id1 = uniquePersistenceId(tag)
    val id2 = uniquePersistenceId(tag)
    val pa1 = system.actorOf(Props(new TestPersistentActor(id1)))
    val pa2 = system.actorOf(Props(new TestPersistentActor(id2)))

    Thread.sleep(halfRefreshIntervalInMills)

    Await.ready(ask(pa1, TestCmd("a1")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa2, TestCmd("a2")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa1, TestCmd("b1")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa2, TestCmd("b2")), timeout)

    Thread.sleep(halfRefreshIntervalInMills)

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

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    Await.ready(ask(pa1, TestCmd("c1")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa2, TestCmd("c2")), timeout)

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectNoMsg(FiniteDuration(halfRefreshIntervalInMills, TimeUnit.MILLISECONDS)) // The stream should have stopped
  }

}

class JdbcReadJournalTest5 extends JdbcReadJournalTestBase("JdbcReadJournalTest5") {
  test("eventsByTag - multiple tags") {

    val tag1 = "pb"
    val tag2 = "pb"

    val source: Source[EventEnvelope, NotUsed] =
      readJournal.eventsByTag(s"$tag1|$tag2", 0)


    val id1 = uniquePersistenceId(tag1)
    val id2 = uniquePersistenceId(tag2)
    val pa1 = system.actorOf(Props(new TestPersistentActor(id1)))
    val pa2 = system.actorOf(Props(new TestPersistentActor(id2)))

    val streamResult = TestProbe()

    // materialize stream, consuming events
    implicit val mat = ActorMaterializer()
    source.runForeach {
      event =>
        println("Stream received Event: " + event)
        streamResult.ref ! event
    }

    Thread.sleep(halfRefreshIntervalInMills)

    Await.ready(ask(pa1, TestCmd("a1")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa2, TestCmd("a2")), timeout)
    Thread.sleep(100)

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(1, id1, 1, TestEvent("a1")),
      EventEnvelope(2, id2, 2, TestEvent("a2")))

    Await.ready(ask(pa1, TestCmd("b1")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa2, TestCmd("b2")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa1, TestCmd("c1")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa2, TestCmd("c2")), timeout)


    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(3, id1, 3, TestEvent("b1")),
      EventEnvelope(4, id2, 4, TestEvent("b2")),
      EventEnvelope(5, id1, 5, TestEvent("c1")),
      EventEnvelope(6, id2, 6, TestEvent("c2")))

    Await.ready(ask(pa1, TestCmd("d1")), timeout)
    Thread.sleep(100)
    Await.ready(ask(pa2, TestCmd("d2")), timeout)

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle
    streamResult.expectMsgAllOf(
      EventEnvelope(7, id1, 7, TestEvent("d1")),
      EventEnvelope(8, id2, 8, TestEvent("d2")))
  }
}

case class TestCmd(v: String)

case class TestEvent(v: String)

class TestPersistentActor(val persistenceId: String) extends Actor with PersistentActor with ActorLogging {
  override def receiveRecover: Receive = {
    case x: Any => log.debug(s"receiveRecover: $x")
  }

  override def receiveCommand: Receive = {
    case TestCmd(v) =>
      val event = TestEvent(v)
      persist(event) {
        e =>
          log.info(s"Persisted $event")
          sender ! "ok"
      }
  }

}