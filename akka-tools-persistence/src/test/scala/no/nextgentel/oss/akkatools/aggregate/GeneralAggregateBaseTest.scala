package no.nextgentel.oss.akkatools.aggregate

import java.util.UUID

import akka.actor.{ActorPath, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import no.nextgentel.oss.akkatools.testing.AggregateTesting
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers}
import org.slf4j.LoggerFactory

class GeneralAggregateBaseTest (_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {

  def this() = this(ActorSystem("test-actor-system", ConfigFactory.load("application-test.conf")))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val log = LoggerFactory.getLogger(getClass)
  private def generateId() = UUID.randomUUID().toString

  val seatIds = List("s1","id-used-in-Failed-in-onAfterValidationSuccess", "s2", "s3-This-id-is-going-to-be-discarded", "s4")

  trait TestEnv extends AggregateTesting[XState] {
    val id = generateId()
    val dest = TestProbe()
    val main = system.actorOf(Props( new XAggregate(null, dmForwardAndConfirm(dest.ref).path)), "XAggregate-" + id)

    def assertState(correctState:XState): Unit = {
      assert(getState() == correctState)
    }

  }


  test("basic flow") {
    new TestEnv {
      assertState(XState(0))

      sendDMBlocking(main, AddValueCmd(0))
      assertState(XState(1))

      dest.expectMsg(ValueWasAdded(1))

      sendDMBlocking(main, AddValueCmd(1))
      assertState(XState(2))

      dest.expectMsg(ValueWasAdded(2))
    }
  }

}

trait XEvent

case class XAddEvent(from:Int) extends XEvent

case class XState(value:Int) extends AggregateState[XEvent, XState] {
  override def transition(event: XEvent): XState = {
    event match {
      case e:XAddEvent =>
        if ( value == e.from)
          XState(value + 1)
        else
          throw new AggregateError("Invalid from-value")
      case e:Any => throw new AggregateError("Invalid event")
    }
  }
}


case class AddValueCmd(from:Int) extends AggregateCmd {
  override def id(): String = ???
}

case class ValueWasAdded(newValue:Int)

class XAggregate(dmSelf:ActorPath, dest:ActorPath) extends GeneralAggregateBase[XEvent, XState](dmSelf) {
  override var state: XState = XState(0)

  // Called AFTER event has been applied to state
  override def generateDMs(event: XEvent, previousState: XState): ResultingDMs = {
    val e = event.asInstanceOf[XAddEvent]
    assert( (state.value - 1) == e.from )
    assert( (state.value - 1) == previousState.value)
    ResultingDMs(ValueWasAdded(state.value), dest)
  }

  override def cmdToEvent: PartialFunction[AggregateCmd, ResultingEvent[XEvent]] = {
    case c:AddValueCmd => ResultingEvent(XAddEvent(c.from))
  }

  // Used as prefix/base when constructing the persistenceId to use - the unique ID is extracted runtime from actorPath which is construced by Sharding-coordinator
  override def persistenceIdBase(): String = "/x/"
}
