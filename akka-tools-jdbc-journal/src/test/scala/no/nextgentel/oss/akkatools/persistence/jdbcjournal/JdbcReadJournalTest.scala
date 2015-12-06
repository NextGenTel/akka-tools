package no.nextgentel.oss.akkatools.persistence.jdbcjournal


import akka.actor.{Props, ActorLogging, Actor, ActorSystem}
import akka.persistence.PersistentActor
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{TestProbe, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, BeforeAndAfterAll, FunSuiteLike}
import org.slf4j.LoggerFactory


class JdbcReadJournalTest(_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with BeforeAndAfterAll with Matchers {

  def this() = this(ActorSystem("JdbcReadJournalTest", ConfigFactory.load("application-test.conf")))

  val log = LoggerFactory.getLogger(getClass)
  lazy val dataSource = DataSourceUtil.createDataSource("MyJournalSpec", "akka-tools-jdbc-journal-liquibase.sql")

  val errorHandler = new JdbcJournalErrorHandler {
    override def onError(e: Exception): Unit = log.error("JdbcJournalErrorHandler.onError", e)
  }

  JdbcJournal.init(JdbcJournalConfig(dataSource, None, errorHandler, new ProcessorIdSplitterLastSomethingImpl('-')))

  val readJournal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.identifier)

  val halfRefreshIntervalInMills:Long = readJournal.refreshInterval.toMillis/2

  test("query") {

    val persistenceId = "pa-1"
    val pa = system.actorOf(Props(new TestPersistentActor(persistenceId)))

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

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(1, persistenceId, 1, TestEvent("a")))

    pa ! TestCmd("b")
    pa ! TestCmd("c")

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle

    streamResult.expectMsgAllOf(
      EventEnvelope(2, persistenceId, 2, TestEvent("b")),
      EventEnvelope(3, persistenceId, 3, TestEvent("c")))

    pa ! TestCmd("d")

    Thread.sleep(halfRefreshIntervalInMills * 2) // Skip to next read cycle
    streamResult.expectMsgAllOf(
      EventEnvelope(4, persistenceId, 4, TestEvent("d")))

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