package no.nextgentel.oss.akkatools.aggregate.aggregateTest1_usingAggregateState

import java.util.UUID

import akka.actor.{ActorPath, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import no.nextgentel.oss.akkatools.aggregate._
import no.nextgentel.oss.akkatools.persistence.{DurableMessage, DurableMessageReceived}
import no.nextgentel.oss.akkatools.testing.AggregateTesting
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.slf4j.LoggerFactory


/**
  * Testing aggregate using AggregateState
  */

class GeneralAggregateBaseTest_usingAggregateState(_system:ActorSystem) extends TestKit(_system) with AnyFunSuiteLike with BeforeAndAfterAll with BeforeAndAfter {

  def this() = this(ActorSystem("test-actor-system", ConfigFactory.load("application-test.conf")))

  override def afterAll(): Unit = {
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

  test("auto-handling when re-receiving idempotent dm-cmds") {
    new TestEnv {
      assertState(XState(0))

      val sender = TestProbe()
      val confirmationRoutingInfo1 = "used-as-id-when-using-sharding"
      val dm1 = DurableMessage(1L, AddValueCmd(0), sender.ref.path, confirmationRoutingInfo1)
      main ! dm1
      assertState(XState(1))

      sender.expectMsg(DurableMessageReceived(1L, confirmationRoutingInfo1))

      dest.expectMsg(ValueWasAdded(1))

      // Now we resend the first dm
      main ! dm1
      // Assert that we get the DMReceived one more time, but the state should not have changed.
      sender.expectMsg(DurableMessageReceived(1L, confirmationRoutingInfo1))
      assertState(XState(1)) // Should stay the same
      // We should NOT get the same ValueWasAdded again - we should not get any at all
      dest.expectNoMessage()

      // ----------------------------------------------
      // Now we're sending another CMD - AddValueCmdSendingAckBackUsingDMInSuccessHandler -  as dm2
      // It will not confirm the DM directly, but send 'ack' back from it successhandler using sendAsDM which
      // will resend (with new payload) the original dm back to us.

      val dm2 = DurableMessage(2L, AddValueCmdSendingAckBackUsingDMInSuccessHandler(1), sender.ref.path, confirmationRoutingInfo1)
      sender.send(main, dm2)

      // Assert that we receive a new dm using the same info - but with 'ack' as payload
      sender.expectMsg(dm2.withNewPayload(s"ack-1"))
      assertState(XState(2))

      dest.expectMsg(ValueWasAdded(2))

      // Now we resend dm2
      sender.send(main, dm2)
      // Assert that we receive a new dm using the same info - but with 'ack' as payload
      sender.expectMsg(dm2.withNewPayload(s"ack-1"))
      assertState(XState(2)) // Should stay the same
      // We should NOT get the same ValueWasAdded again - we should not get any at all
      dest.expectNoMessage()
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

case class AddValueCmdSendingAckBackUsingDMInSuccessHandler(from:Int) extends AggregateCmd {
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
    case c:AddValueCmdSendingAckBackUsingDMInSuccessHandler =>
      ResultingEvent {
        XAddEvent(c.from)
      }.onSuccess {
        log.info(s"Success-handler: ending ack as DM back to ${sender().path} ")
        sendAsDM(s"ack-${c.from}", sender().path)
      }
  }

  // Used as prefix/base when constructing the persistenceId to use - the unique ID is extracted runtime from actorPath which is construced by Sharding-coordinator
  override def persistenceIdBase(): String = "/x/"
}
