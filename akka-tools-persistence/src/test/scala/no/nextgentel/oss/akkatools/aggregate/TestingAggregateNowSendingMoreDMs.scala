package no.nextgentel.oss.akkatools.aggregate.TestingAggregateNowSendingMoreDMsPackage

import java.util.UUID

import akka.actor.{ActorPath, ActorSystem, PoisonPill, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import no.nextgentel.oss.akkatools.aggregate._
import no.nextgentel.oss.akkatools.persistence.{DurableMessage, SendAsDM}
import no.nextgentel.oss.akkatools.testing.{AggregateStateGetter, AggregateTesting}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers}
import org.slf4j.LoggerFactory

class TestingAggregateNowSendingMoreDMsTest (_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {

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
    val aggregateId = "XAggregate-" + id
    val main = system.actorOf(Props( new XAggregateVersion1Sending1DM(null, dest.ref.path)), aggregateId)

    def assertState(correctState:XState): Unit = {
      assert(getState() == correctState)
    }

  }


  test("working with aggregate that has been changed to send more DMs than before") {
    new TestEnv {
      assertState(XState(0))

      sendDMBlocking(main, AddValueCmd(0))
      assertState(XState(1))

      dest.expectMsg(DurableMessage(1, ValueWasAdded(1), main.path.toString, aggregateId)).confirm(system, null)

      sendDMBlocking(main, AddValueCmd(1))
      assertState(XState(2))

      dest.expectMsg(DurableMessage(2, ValueWasAdded(2), main.path.toString, aggregateId)).confirm(system, null)
      dest.expectNoMsg()

      // stop it
      main ! PoisonPill
      Thread.sleep(500)
      // Make sure it stops before we continue

      // ---------------------------------------------
      // Recreate it to make it recover.
      val dest2 = TestProbe()
      val main2 = system.actorOf(Props(new XAggregateVersion1Sending1DM(null, dest2.ref.path)), aggregateId)

      // Make sure we recover into correct state
      assert(XState(2) == AggregateStateGetter[Any](main2).getState(Some(aggregateId)).asInstanceOf[XState])

      // And make sure no DMs where actually sent
      dest2.expectNoMsg()

      // Stop it
      main2 ! PoisonPill
      Thread.sleep(500) // Make sure it stops before we continue

      // ---------------------------------------------
      // Start it again, but now use XAggregateVersion2Sending2DMs which is
      // going to send two DMs where the prev one used to send only one.
      // When replaying, this should result in the extra one been sent.

      // Lets do this twice just to make sure
      for (i <- 1 to 2) {

        println("Using XAggregateVersion2Sending2DMs")
        // Recreate it to make it recover.
        val dest3 = TestProbe()
        val main3 = system.actorOf(Props(new XAggregateVersion2Sending2DMs(null, dest3.ref.path)), aggregateId)

        // Make sure we recover into correct state
        assert(XState(2) == AggregateStateGetter[Any](main3).getState(Some(aggregateId)).asInstanceOf[XState])

        // Since we have not done anything to prevent us from sending this extra DM, we should expect it
        dest3.expectMsg(DurableMessage(3, ValueWasAdded(2), main.path.toString, aggregateId)) // NOT CONFIRMING IT - .confirm(system, null)
        dest3.expectMsg(DurableMessage(4, ValueWasAdded(102), main.path.toString, aggregateId)) // NOT CONFIRMING IT - .confirm(system, null)

        dest3.expectNoMsg()

        // Stop it
        main3 ! PoisonPill
        Thread.sleep(500) // Make sure it stops before we continue
      }

      // Lets do this twice just to make sure
      for (i <- 1 to 2) {
        // Now we're doing it again but this time we're using XAggregateVersion3Sending2DMs which is coded
        // to set DMGeneratingVersion == 1.. This should fix the problem and NOT send the extra DMs
        println("Using XAggregateVersion3Sending2DMs")
        // Recreate it to make it recover.
        val dest4 = TestProbe()
        val main4 = system.actorOf(Props(new XAggregateVersion3Sending2DMs(null, dest4.ref.path)), aggregateId)

        // Make sure we recover into correct state
        assert(XState(2) == AggregateStateGetter[Any](main4).getState(Some(aggregateId)).asInstanceOf[XState])

        dest4.expectNoMsg()

        // Stop it
        main4 ! PoisonPill
        Thread.sleep(500) // Make sure it stops before we continue
      }

      // Now do it again, but this time we're going to send a new cmd, which should result in new event and new dms. - nothing special since we have already fixed the problem

      // Now we're doing it again but this time we're using XAggregateVersion3Sending2DMs which is coded
      // to set DMGeneratingVersion == 1.. This should fix the problem and NOT send the extra DMs
      println("Using XAggregateVersion3Sending2DMs")
      // Recreate it to make it recover.
      val dest4 = TestProbe()
      val main4 = system.actorOf(Props(new XAggregateVersion3Sending2DMs(null, dest4.ref.path)), aggregateId)

      // Make sure we recover into correct state
      assert(XState(2) == AggregateStateGetter[Any](main4).getState(Some(aggregateId)).asInstanceOf[XState])

      dest4.expectNoMsg()

      sendDMBlocking(main4, AddValueCmd(2))
      // Make sure we recover into correct state
      assert(XState(3) == AggregateStateGetter[Any](main4).getState(Some(aggregateId)).asInstanceOf[XState])

      // Since we have not done anything to prevent us from sending this extra DM, we should expect it
      dest4.expectMsg(DurableMessage(5, ValueWasAdded(3), main4.path.toString, aggregateId)) // NOT CONFIRMING IT - .confirm(system, null)
      dest4.expectMsg(DurableMessage(6, ValueWasAdded(103), main4.path.toString, aggregateId)) // NOT CONFIRMING IT - .confirm(system, null)

      dest4.expectNoMsg()

      // Stop it
      main4 ! PoisonPill
      Thread.sleep(500) // Make sure it stops before we continue

      // ----------------
      // Do it all again - now with a new DMGeneratingVersion which sends 3 DMs pr event

      // Lets do this twice just to make sure
      for (i <- 1 to 2) {
        // Now we're doing it again but this time we're using XAggregateVersion3Sending2DMs which is coded
        // to set DMGeneratingVersion == 1.. This should fix the problem and NOT send the extra DMs
        println("Using XAggregateVersion4Sending3DMs")
        // Recreate it to make it recover.
        val dest4 = TestProbe()
        val main4 = system.actorOf(Props(new XAggregateVersion4Sending3DMs(null, dest4.ref.path)), aggregateId)

        // Make sure we recover into correct state
        assert(XState(3) == AggregateStateGetter[Any](main4).getState(Some(aggregateId)).asInstanceOf[XState])

        dest4.expectNoMsg()

        // Stop it
        main4 ! PoisonPill
        Thread.sleep(500) // Make sure it stops before we continue
      }

      // ----------------

      // Now we're trying to go back to the original one - using only one DM.
      // When using this one, the journal now actually have stored too many DurableMessageReceived-events..
      // When processing these "too-many-DurableMessageReceived", we're just going to ignore them...
      // So we're now going to make sure we can recover the the 3 events, not re-sending anything, and
      // make sure that when we add the 4. event, it gets sent..

      for (i <- 1 to 2) {
        println("Using XAggregateVersion5Sending1DM")
        // Recreate it to make it recover.
        val dest5 = TestProbe()
        val main5 = system.actorOf(Props(new XAggregateVersion5Sending1DM(null, dest5.ref.path)), aggregateId)

        // Make sure we recover into correct state
        assert(XState(3) == AggregateStateGetter[Any](main5).getState(Some(aggregateId)).asInstanceOf[XState])

        dest5.expectNoMsg()

        // Stop it
        main5 ! PoisonPill
        Thread.sleep(500) // Make sure it stops before we continue
      }

      //------

      println("Using XAggregateVersion5Sending1DM")
      // Recreate it to make it recover.
      val dest5 = TestProbe()
      val main5 = system.actorOf(Props(new XAggregateVersion5Sending1DM(null, dest5.ref.path)), aggregateId)

      // Make sure we recover into correct state
      assert(XState(3) == AggregateStateGetter[Any](main5).getState(Some(aggregateId)).asInstanceOf[XState])

      dest5.expectNoMsg()

      // Send the new cmd
      sendDMBlocking(main5, AddValueCmd(3))
      // Make sure we recover into correct state
      assert(XState(4) == AggregateStateGetter[Any](main5).getState(Some(aggregateId)).asInstanceOf[XState])

      dest5.expectMsg(DurableMessage(4, ValueWasAdded(4), main5.path.toString, aggregateId)) // NOT CONFIRMING IT - .confirm(system, null)

      // Stop it
      main5 ! PoisonPill
      Thread.sleep(500) // Make sure it stops before we continue

    }
  }

  test("Migrating to new dbGeneratingVersion with nothing to fix") {
    new TestEnv {
      assertState(XState(0))

      sendDMBlocking(main, AddValueCmd(0))
      assertState(XState(1))

      dest.expectMsg(DurableMessage(1, ValueWasAdded(1), main.path.toString, aggregateId)).confirm(system, null)

      // stop it
      main ! PoisonPill
      Thread.sleep(500)

      // Start again with new impl using new dmGeneratingVersion but still only sending one DM pr event.
      for( i <- 1 to 2) { // do it twice
        val d = TestProbe()
        val m = system.actorOf(Props(new XAggregateVersion5Sending1DM(null, d.ref.path)), aggregateId)

        // Make sure we recover into correct state
        assert(XState(1) == AggregateStateGetter[Any](m).getState(Some(aggregateId)).asInstanceOf[XState])

        d.expectNoMsg()

        // stop it
        m ! PoisonPill
        Thread.sleep(500)
      }

      // Start it again

      {
        val d = TestProbe()
        val m = system.actorOf(Props(new XAggregateVersion5Sending1DM(null, d.ref.path)), aggregateId)

        // Make sure we recover into correct state
        assert(XState(1) == AggregateStateGetter[Any](m).getState(Some(aggregateId)).asInstanceOf[XState])

        sendDMBlocking(m, AddValueCmd(1))
        assert(XState(2) == AggregateStateGetter[Any](m).getState(Some(aggregateId)).asInstanceOf[XState])

        d.expectMsg(DurableMessage(2, ValueWasAdded(2), main.path.toString, aggregateId)) // NOT confirming it - .confirm(system, null)

        // stop it
        m ! PoisonPill
        Thread.sleep(500)
      }

      // Start it again - make sure the unconfrmed message is resent
      {
        val d = TestProbe()
        val m = system.actorOf(Props(new XAggregateVersion5Sending1DM(null, d.ref.path)), aggregateId)

        // Make sure we recover into correct state
        assert(XState(2) == AggregateStateGetter[Any](m).getState(Some(aggregateId)).asInstanceOf[XState])

        d.expectMsg(DurableMessage(2, ValueWasAdded(2), main.path.toString, aggregateId)).confirm(system, null)

        // stop it
        m ! PoisonPill
        Thread.sleep(500)
      }

      // Start it again - make sure nothing gets sent
      {
        val d = TestProbe()
        val m = system.actorOf(Props(new XAggregateVersion5Sending1DM(null, d.ref.path)), aggregateId)

        // Make sure we recover into correct state
        assert(XState(2) == AggregateStateGetter[Any](m).getState(Some(aggregateId)).asInstanceOf[XState])

        d.expectNoMsg()

        // stop it
        m ! PoisonPill
        Thread.sleep(500)
      }


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

class XAggregateVersion1Sending1DM(dmSelf:ActorPath, dest:ActorPath) extends GeneralAggregateBase[XEvent, XState](dmSelf) {
  override var state: XState = XState(0)

  // Called AFTER event has been applied to state
  override def generateDMs(event: XEvent, previousState: XState): ResultingDMs = {
    val e = event.asInstanceOf[XAddEvent]
    assert( (state.value - 1) == e.from )
    assert( (state.value - 1) == previousState.value)
    internalGenerateResultingDMs()
  }

  def internalGenerateResultingDMs():ResultingDMs = {
    ResultingDMs(ValueWasAdded(state.value), dest)
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

class XAggregateVersion2Sending2DMs(dmSelf:ActorPath, dest:ActorPath) extends XAggregateVersion1Sending1DM(dmSelf, dest) {
  override def internalGenerateResultingDMs(): ResultingDMs = {
    ResultingDMs( List(
      SendAsDM(ValueWasAdded(state.value), dest),
      SendAsDM(ValueWasAdded(state.value + 100), dest)
    ) )
  }
}


class XAggregateVersion3Sending2DMs(dmSelf:ActorPath, dest:ActorPath) extends XAggregateVersion2Sending2DMs(dmSelf, dest) {
  // Override this in your code to set the dmGeneratingVersion your code is currently using.
  override protected def getDMGeneratingVersion(): Int = 1
}

class XAggregateVersion4Sending3DMs(dmSelf:ActorPath, dest:ActorPath) extends XAggregateVersion1Sending1DM(dmSelf, dest) {
  // Override this in your code to set the dmGeneratingVersion your code is currently using.
  override protected def getDMGeneratingVersion(): Int = 2

  override def internalGenerateResultingDMs(): ResultingDMs = {
    ResultingDMs( List(
      SendAsDM(ValueWasAdded(state.value), dest),
      SendAsDM(ValueWasAdded(state.value + 100), dest),
      SendAsDM(ValueWasAdded(state.value + 200), dest)
    ) )
  }
}

class XAggregateVersion5Sending1DM(dmSelf:ActorPath, dest:ActorPath) extends XAggregateVersion1Sending1DM(dmSelf, dest) {
  // Override this in your code to set the dmGeneratingVersion your code is currently using.
  override protected def getDMGeneratingVersion(): Int = 3

}