package no.nextgentel.oss.akkatools.aggregate.aggregateTest_usingAggregateStateBase

import java.util.UUID

import akka.actor.{ActorPath, ActorSystem, Props}
import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotMetadata, SnapshotOffer}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import no.nextgentel.oss.akkatools.aggregate._
import no.nextgentel.oss.akkatools.testing.AggregateTesting
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers}
import org.slf4j.LoggerFactory


/**
  * Testing that internal snapshot related messages are handled
  */
class GeneralAggregateBaseTest_handleSnapshotMessages(_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {

  def this() = this(ActorSystem("test-actor-system", ConfigFactory.load("application-test.conf")))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val log = LoggerFactory.getLogger(getClass)
  private def generateId() = UUID.randomUUID().toString

  trait TestEnv extends AggregateTesting[StringState] {
    val id = generateId()
    val dest = TestProbe()
    val main = system.actorOf(Props( new SillyAggr(null, dmForwardAndConfirm(dest.ref).path)), "SillyAggr-" + id)

    def assertState(correctState:StringState): Unit = {
      assert(getState() == correctState)
    }

  }

  test("test that snapshot handling messages triggers handling") {
    new TestEnv {
      assertState(StringState("START"))

      sendDMBlocking(main,SaveSnapshotFailure(SnapshotMetadata("",1,1),null))
      assertState(StringState("FAIL_SNAP"))

      sendDMBlocking(main,SaveSnapshotSuccess(SnapshotMetadata("",1,1)))
      assertState(StringState("SUCCESS_SNAP"))

      sendDMBlocking(main,DeleteMessagesFailure(null,2))
      assertState(StringState("FAIL_MSG"))

      sendDMBlocking(main,DeleteMessagesSuccess(4))
      assertState(StringState("SUCCESS_MSG"))

      sendDMBlocking(main,SaveSnapshotOfCurrentState(None))
      assertState(StringState("WAT"))

      sendDMBlocking(main,SaveSnapshotOfCurrentState(None))
      assertState(StringState("SAVED"))

    }
  }

}



class SillyAggr(dmSelf:ActorPath, dest:ActorPath) extends GeneralAggregateBase[StringEv, StringState](dmSelf) {
  override var state: StringState = StringState("START")

  // Called AFTER event has been applied to state
  override def generateDMs(event: StringEv, previousState: StringState): ResultingDMs = {
    ResultingDMs(List())
  }

  override def cmdToEvent: PartialFunction[AggregateCmd, ResultingEvent[StringEv]] = {
    case x : AggregateCmd  => ResultingEvent(StringEv("WAT"))
  }

  /**
   * Always accepts a snapshot offer and makes a snapshot.
   * If that is a success it deletes the events.
   *
   * On error moessages it will attempt to update state (this is nonsensical, but used to check that these
   * messages are indeed processed)
   *
   */
   override val aggregatePersistenceHandling: AggregatePersistenceHandler = new AggregatePersistenceHandler {

    override val onSnapshotOffer: PartialFunction[SnapshotOffer, Unit] = { case offer =>
      state = offer.snapshot.asInstanceOf[StringState]
    }

    override val acceptSnapshotRequest: PartialFunction[SaveSnapshotOfCurrentState, Boolean] = { case x =>
      if(state == StringState("WAT")) {
        state = StringState("SAVED")
        true
      }
      else {
        state = StringState("WAT") //So it works second time
        false
      }
    }

    override val onSnapshotSuccess: PartialFunction[SaveSnapshotSuccess, Unit] = { case x =>
      state = StringState("SUCCESS_SNAP")
    }
    override val onSnapshotFailure: PartialFunction[SaveSnapshotFailure, Unit] = { case x =>
      state = StringState("FAIL_SNAP")
    }
    override val onDeleteMessagesSuccess: PartialFunction[DeleteMessagesSuccess, Unit] = { case x =>
      state = StringState("SUCCESS_MSG")
    }
    override val onDeleteMessagesFailure: PartialFunction[DeleteMessagesFailure, Unit] = { case x =>
      state = StringState("FAIL_MSG")
    }
  }


  // Used as prefix/base when constructing the persistenceId to use - the unique ID is extracted runtime from actorPath which is construced by Sharding-coordinator
  override def persistenceIdBase(): String = "/x/"
}

case class StringEv(data: String)

case class StringState(data:String) extends AggregateStateBase[StringEv, StringState] {
  override def transitionState(event: StringEv): StateTransition[StringEv, StringState] = ???
}