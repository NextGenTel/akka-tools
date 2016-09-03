package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.persistence.{SnapshotOffer, PersistentActor}
import akka.serialization.SerializerWithStringManifest
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, BeforeAndAfterAll, BeforeAndAfter, FunSuiteLike}
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class JdbcSnapshotStoreTest (_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with BeforeAndAfter with BeforeAndAfterAll with Matchers {

  def this() = this(ActorSystem("JdbcSnapshotStoreTest", ConfigFactory.load("application-test.conf")))

  val log = LoggerFactory.getLogger(getClass)

  val errorHandler = new JdbcJournalErrorHandler {
    override def onError(e: Exception): Unit = log.error("JdbcJournalErrorHandler.onError", e)
  }

  before {
    // Remember: Since JdbcJournal.init() is static this will break if we run tests in parallel
    JdbcJournal.init(JdbcJournalConfig(DataSourceUtil.createDataSource("JdbcSnapshotStoreTest"), None, errorHandler, new PersistenceIdParserImpl('-')))
  }

  override protected def afterAll(): Unit = {
    val f = system.terminate()
    Await.ready(f, Duration("2s"))
  }

  test("using snapshot") {

    val id = "JdbcSnapshotStoreTest-1"
    var p = system.actorOf(Props(new TestPersistentUsingSnapshotActor(id)))
    assert( "" == getData(p))

    p ! SetDataCmd("x")
    assert( "x" == getData(p))

    p ! PoisonPill
    Thread.sleep(500)

    p = system.actorOf(Props(new TestPersistentUsingSnapshotActor(id)))

    assert( "x" == getData(p))

    p ! SaveSnapshot1Cmd()
    assert( "x" == getData(p))

    p ! PoisonPill
    Thread.sleep(500)

    p = system.actorOf(Props(new TestPersistentUsingSnapshotActor(id)))

    assert( "x" == getData(p))


    p ! SaveSnapshot2Cmd()
    assert( "x" == getData(p))

    p ! PoisonPill
    Thread.sleep(500)

    p = system.actorOf(Props(new TestPersistentUsingSnapshotActor(id)))

    assert( "x" == getData(p))


  }

  def getData(p:ActorRef):String = {
    import akka.pattern.ask
    implicit val timeout = Timeout(5, TimeUnit.SECONDS)

    val f = ask(p, GetDataCmd())
    Await.result(f, timeout.duration).asInstanceOf[String]
  }

}

case class SetDataCmd(v:String)

case class GetDataCmd()

case class SetDataEvent(v:String)

case class SaveSnapshot1Cmd()
case class SaveSnapshot2Cmd()

case class Snapshot1(data:String)
case class Snapshot2(data:String)


class SnapshotSerializerUsingManifest extends SerializerWithStringManifest {
  override def identifier: Int = 145454541

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    assert( "MyManifest" == manifest)
    val s = Snapshot2(new String(bytes))
    println("SnapshotSerializerUsingManifest - fromBinary: " + s)
    s
  }

  override def manifest(o: AnyRef): String = {
    assert(o.isInstanceOf[Snapshot2])
    "MyManifest"
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    o.asInstanceOf[Snapshot2].data.getBytes
  }
}

class TestPersistentUsingSnapshotActor(val persistenceId:String) extends Actor with PersistentActor with ActorLogging {

  var data:String = ""

  override def receiveRecover: Receive = {
    case x:SetDataEvent =>
      log.debug(s"receiveRecover: $x")
      data = x.v
    case SnapshotOffer(_, x:Snapshot1) =>
      log.debug("Got snapshot 1")
      data = x.data
    case SnapshotOffer(_, x:Snapshot2) =>
      log.debug("Got snapshot 2")
      data = x.data

    case x:Any => log.debug("receiveRecover ?: " + x)
  }

  override def receiveCommand: Receive = {
    case GetDataCmd() =>
      log.debug(s"GetDataCmd '$data'")
      sender ! data
    case SetDataCmd(v) =>
      log.debug(s"SetDataCmd '$v'")
      val event = SetDataEvent(v)
      persist( event) {
        e =>
          data = v
          log.info(s"Persisted $event")
      }
    case SaveSnapshot1Cmd() =>
      log.debug("Saving snapshot 1")
      saveSnapshot( Snapshot1(data) )
    case SaveSnapshot2Cmd() =>
      log.debug("Saving snapshot 2")
      saveSnapshot( Snapshot2(data) )
    case x:Any => log.debug("receiveCommand ?: " + x)
  }

}
