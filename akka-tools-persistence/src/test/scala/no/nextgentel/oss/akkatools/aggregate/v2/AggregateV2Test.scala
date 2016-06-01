package no.nextgentel.oss.akkatools.aggregate.v2

import java.util.UUID

import akka.actor.{ActorPath, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import no.nextgentel.oss.akkatools.persistence.SendAsDM
import no.nextgentel.oss.akkatools.aggregate._
import no.nextgentel.oss.akkatools.testing.AggregateTesting
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers}
import org.slf4j.LoggerFactory


class GeneralAggregateV2Test (_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {

  def this() = this(ActorSystem("test-actor-system", ConfigFactory.load("application-test.conf")))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val log = LoggerFactory.getLogger(getClass)

  private def generateId() = UUID.randomUUID().toString


  trait TestEnv extends AggregateTesting[V2TestState] {
    val id = generateId()
    val sender = TestProbe()
    val dest = TestProbe()
    val config = V2TestConfig(dmForwardAndConfirm(dest.ref).path, "someString")
    val main = system.actorOf(Props(new V2TestAggregate(null, config)), "XXAggregate-" + id)

    def assertState(correctState: V2TestState): Unit = {
      assert(getState() == correctState)
    }

  }

  test("Sunshine") {

    new TestEnv {
      assertState(V2TestEmptyState())

      val myData = "ThisIsTheData"

      sendDMBlocking(main, StartCmd(id, myData), sender.ref)

      assertState(V2TestStartedState(myData))

      sender.expectMsg("ok-"+config.customMessage)

      dest.expectMsg("We've started")

      sendDMBlocking(main, CloseCmd(id), sender.ref)

      dest.expectMsg("We've closed")

      assertState(V2TestClosedState(true))

    }

  }

}



case class V2TestConfig(destination: ActorPath, customMessage: String)

case class StartCmd(id: String, data: String) extends AggregateCmd

case class CloseCmd(id: String) extends AggregateCmd


trait V2TestEvent


case class V2TestStartEvent(data: String) extends V2TestEvent

case class V2TestClosedEvent() extends V2TestEvent

trait V2TestState extends AggregateStateV2[V2TestEvent, V2TestConfig, V2TestState]


case class V2TestEmptyState() extends V2TestState {

  override def cmdToEvent = {
    case (aggregate, StartCmd(_, data)) => ResultingEvent {
      V2TestStartEvent(data)
    }.onSuccess {
      aggregate.sender ! "ok-" + aggregate.config.customMessage
    }.onError {
      errorMsg =>
        aggregate.sender ! s"Error: $errorMsg"
    }
  }

  override def eventToState = {
    case e: V2TestStartEvent => V2TestStartedState(e.data)
  }

  override def eventToDMs = Map.empty

}


case class V2TestStartedState(data: String) extends V2TestState {

  override def cmdToEvent = {
    case (aggregate, CloseCmd(_)) =>
      ResultingEvent {
        V2TestClosedEvent()
      }
  }

  override def eventToState = {
    case V2TestClosedEvent() => V2TestClosedState(success = true)
  }

  override def eventToDMs = {
    case (config, e: V2TestStartEvent) => ResultingDMs(SendAsDM("We've started", config.destination))
  }
}


case class V2TestClosedState(success: Boolean) extends V2TestState {

  override def cmdToEvent = Map.empty

  override def eventToState = Map.empty

  override def eventToDMs = {
    case (config, e: V2TestClosedEvent) => ResultingDMs(SendAsDM("We've closed", config.destination))
  }
}


class V2TestAggregate(dmSelf:ActorPath, val config:V2TestConfig) extends GeneralAggregateV2[V2TestEvent, V2TestConfig, V2TestState](dmSelf) {

  override var state:V2TestState = V2TestEmptyState()

  override def persistenceIdBase(): String = "/juhu/"
}