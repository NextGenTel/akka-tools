package no.nextgentel.oss.akkatools.aggregate.aggregateTest_usingAggregateStateBase

import java.util.UUID

import akka.actor.{ActorPath, ActorSystem, PoisonPill, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import no.nextgentel.oss.akkatools.aggregate._
import no.nextgentel.oss.akkatools.persistence.{DurableMessage, DurableMessageReceived}
import no.nextgentel.oss.akkatools.testing.{AggregateStateGetter, AggregateTesting}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers}
import org.slf4j.LoggerFactory


/**
  * Testing aggregate using AggregateStateBase
  */

class GeneralAggregateBaseTest_usingAggregateStateBase(_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {

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
      assertState(XState(2))

      dest.expectMsg(ValueWasAdded(1))
      dest.expectMsg(ValueWasAdded(2))

      sendDMBlocking(main, AddValueCmd(2))
      assertState(XState(4))

      dest.expectMsg(ValueWasAdded(3))
      dest.expectMsg(ValueWasAdded(4))
    }
  }

  test("auto-handling when re-receiving idempotent dm-cmds") {
    new TestEnv {
      assertState(XState(0))

      val sender = TestProbe()
      val confirmationRoutingInfo1 = "used-as-id-when-using-sharding"
      val dm1 = DurableMessage(1L, AddValueCmd(0), sender.ref.path, confirmationRoutingInfo1)
      main ! dm1
      assertState(XState(2))

      sender.expectMsg(DurableMessageReceived(1L, confirmationRoutingInfo1))

      dest.expectMsg(ValueWasAdded(1))
      dest.expectMsg(ValueWasAdded(2))

      // Now we resend the first dm
      main ! dm1
      // Assert that we get the DMReceived one more time, but the state should not have changed.
      sender.expectMsg(DurableMessageReceived(1L, confirmationRoutingInfo1))
      assertState(XState(2)) // Should stay the same
      // We should NOT get the same ValueWasAdded again - we should not get any at all
      dest.expectNoMessage()

      // ----------------------------------------------
      // Now we're sending another CMD - AddValueCmdSendingAckBackUsingDMInSuccessHandler -  as dm2
      // It will not confirm the DM directly, but send 'ack' back from it successhandler using sendAsDM which
      // will resend (with new payload) the original dm back to us.

      val dm2 = DurableMessage(2L, AddValueCmdSendingAckBackUsingDMInSuccessHandler(2), sender.ref.path, confirmationRoutingInfo1)
      sender.send(main, dm2)

      // Assert that we receive a new dm using the same info - but with 'ack' as payload
      sender.expectMsg(dm2.withNewPayload(s"ack-2"))
      assertState(XState(4))

      dest.expectMsg(ValueWasAdded(3))
      dest.expectMsg(ValueWasAdded(4))

      // Now we resend dm2
      sender.send(main, dm2)
      // Assert that we receive a new dm using the same info - but with 'ack' as payload
      sender.expectMsg(dm2.withNewPayload(s"ack-2"))
      assertState(XState(4)) // Should stay the same
      // We should NOT get the same ValueWasAdded again - we should not get any at all
      dest.expectNoMessage()
    }
  }

  test("test recovering") {
    new TestEnv {
      assertState(XState(0))

      sendDMBlocking(main, AddValueCmd(0))
      assertState(XState(2))

      dest.expectMsg(ValueWasAdded(1))
      dest.expectMsg(ValueWasAdded(2))

      sendDMBlocking(main, AddValueCmd(2))
      assertState(XState(4))

      dest.expectMsg(ValueWasAdded(3))
      dest.expectMsg(ValueWasAdded(4))

      Thread.sleep(2000)
      // kill it
      system.stop(main)

      // Wait for it to die
      Thread.sleep(2000)

      // recreate it
      val dest2 = TestProbe()
      val recoveredMain = system.actorOf(Props( new XAggregate(null, dmForwardAndConfirm(dest2.ref).path)), "XAggregate-" + id)

      // get its state
      val recoveredState = AggregateStateGetter[Any](recoveredMain).getState(None).asInstanceOf[XState]
      assert( recoveredState == XState(4))

      // make sure we get no msgs
      dest2.expectNoMessage()

    }
  }

}

trait XEvent

case class XAddEvent(from:Int) extends XEvent

case class XAddSecondEvent(from:Int) extends XEvent

case class XState(value:Int) extends AggregateStateBase[XEvent, XState] {


  override def transitionState(event: XEvent): StateTransition[XEvent, XState] = {
    event match {
      case e:XAddEvent =>
        if ( value == e.from) {
          StateTransition(XState(value + 1), newEvent = Some(XAddSecondEvent(value + 1)))
        }
        else
          throw new AggregateError("Invalid from-value")

      case e:XAddSecondEvent =>
        if ( value == e.from) {
          StateTransition(XState(value + 1), None)
        }
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
    event match {
      case e:XAddEvent =>
        assert( (state.value - 1) == e.from )
        assert( (state.value - 1) == previousState.value)
        ResultingDMs(ValueWasAdded(state.value), dest)
      case e:XAddSecondEvent =>
        assert( (state.value - 1) == e.from )
        assert( (state.value - 1) == previousState.value)
        ResultingDMs(ValueWasAdded(state.value), dest)
      case _ => ResultingDMs(List())
    }
  }

  override def cmdToEvent: PartialFunction[AggregateCmd, ResultingEvent[XEvent]] = {
    case c:AddValueCmd => ResultingEvent(XAddEvent(c.from))
    case c:AddValueCmdSendingAckBackUsingDMInSuccessHandler =>
      ResultingEvent {
        XAddEvent(c.from)
      }.onSuccess {
        log.info(s"Success-handler: ending ack as DM back to ${sender.path} ")
        sendAsDM(s"ack-${c.from}", sender.path)
      }
  }

  // Used as prefix/base when constructing the persistenceId to use - the unique ID is extracted runtime from actorPath which is construced by Sharding-coordinator
  override def persistenceIdBase(): String = "/x/"
}
