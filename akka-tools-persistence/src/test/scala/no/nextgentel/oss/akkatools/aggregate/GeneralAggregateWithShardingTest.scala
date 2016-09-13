package no.nextgentel.oss.akkatools.aggregate

import java.util.{Arrays, UUID}

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import no.nextgentel.oss.akkatools.aggregate.testAggregate.StateName._
import no.nextgentel.oss.akkatools.aggregate.testAggregate.{StateName, _}
import no.nextgentel.oss.akkatools.testing.AggregateTesting
import org.scalatest._
import org.slf4j.LoggerFactory

class GeneralAggregateWithShardingTest(_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {

  def this() = this(ActorSystem("test-actor-system", ConfigFactory.parseString(
    s"""akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
        |akka.remote.enabled-transports = ["akka.remote.netty.tcp"]
        |akka.remote.netty.tcp.hostname="localhost"
        |akka.remote.netty.tcp.port=42996
        |akka.cluster.seed-nodes = ["akka.tcp://test-actor-system@localhost:42996"]
    """.stripMargin
  ).withFallback(ConfigFactory.load("application-test.conf"))))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val log = LoggerFactory.getLogger(getClass)
  private def generateId() = UUID.randomUUID().toString

  val seatIds = List("s1","id-used-in-Failed-in-onAfterValidationSuccess", "s2", "s3-This-id-is-going-to-be-discarded", "s4")

  trait TestEnv extends AggregateTesting[BookingState] {
    val id = generateId()
    val printShop = TestProbe()
    val cinema = TestProbe()
    val onSuccessDmForwardReceiver = TestProbe()

    val starter = new AggregateStarterSimple("booking", system).withAggregatePropsCreator {
      dmSelf =>
        BookingAggregate.props(dmSelf, dmForwardAndConfirm(printShop.ref).path, dmForwardAndConfirm(cinema.ref).path, seatIds, dmForwardAndConfirm(onSuccessDmForwardReceiver.ref).path)
    }

    val main = starter.dispatcher
    starter.start()

    def assertState(correctState:BookingState): Unit = {
      assert(getState(id) == correctState)
    }

  }




  test("normal flow") {

    new TestEnv {

      // Make sure we start with empty state
      assertState(BookingState.empty())

      val maxSeats = 2
      val sender = TestProbe()
      // Open the booking
      println("1")
      sendDMBlocking(main, OpenBookingCmd(id, maxSeats), sender.ref)
      println("2")
      assertState(BookingState(OPEN, maxSeats, Set()))

    }
  }
}






