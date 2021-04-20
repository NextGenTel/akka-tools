package no.nextgentel.oss.akkatools.aggregate.aggregateTest_usingAggregateStateBase

import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.actor.{ActorPath, ActorRef, ActorSystem, Props}
import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotMetadata, SnapshotOffer}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import no.nextgentel.oss.akkatools.aggregate.{AggregateCmd, _}
import no.nextgentel.oss.akkatools.testing.{AggregateStateGetter, AggregateTesting}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.funsuite.AnyFunSuiteLike
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration}


/**
  * Testing that internal snapshot related messages are handled
  */
class GeneralAggregateBaseTest_dropDeleteMessagesTest(_system:ActorSystem) extends TestKit(_system) with AnyFunSuiteLike with BeforeAndAfterAll with BeforeAndAfter {

  def this() = this(ActorSystem("test-actor-system", ConfigFactory.load("application-test.conf")))

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  val log = LoggerFactory.getLogger(getClass)
  private def generateId() = UUID.randomUUID().toString

  trait TestEnv extends AggregateTesting[StringState] {
    val id = generateId()
    val dest = TestProbe()
    val main = system.actorOf(Props( new DroppingAggr(null, dmForwardAndConfirm(dest.ref).path)), "DroppingAggr-" + id)

    def assertState(correctState:StringState): Unit = {
      assert(getState() == correctState)
    }

    def fetchState(): StringState = {
      getState()
    }

  }

  test("test that snapshot handling messages triggers handling") {
    new TestEnv {
      def aggregateState(ref : ActorRef): StringState = {
        AggregateStateGetter[Any](ref).getState(None).asInstanceOf[StringState]
      }

      assertState(StringState("START"))

      sendDMBlocking(main,OneCmd("WAT"))
      assertState(StringState("WAT"))

      sendDMBlocking(main,SaveSnapshotOfCurrentState(None,true))
      assertState(StringState("WAT"))

      //Wait for first drop attempt
      awaitCond(!TestStore.first.get(),Duration("5s"),Duration("0.05s"))

      val stopFut = akka.pattern.gracefulStop(main,FiniteDuration(5,"s"))
      assert(Await.result(stopFut, Duration("5s")))

      // recreate it
      val dest2 = TestProbe()
      val recoveredMain = system.actorOf(Props( new DroppingAggr(null, dmForwardAndConfirm(dest2.ref).path)), "DroppingAggr-" + id)
      awaitCond( TestStore.recoverCount.get() == 1,Duration("5s"),Duration("0.05s"))
      awaitCond( TestStore.deleted.get() == 1,Duration("5s"),Duration("0.05s"))
      val state = aggregateState(recoveredMain);
      awaitCond( state ==  StringState("WAT"))

      // make sure we get no msgs
      dest2.expectNoMessage()

      // kill it
      val stopFut2 = akka.pattern.gracefulStop(recoveredMain,FiniteDuration(5,"s"))
      assert(Await.result(stopFut2, Duration("5s")))

      // recreate it
      val dest3 = TestProbe()
      val recoveredMain2 = system.actorOf(Props( new DroppingAggr(null, dmForwardAndConfirm(dest3.ref).path)), "DroppingAggr-" + id)
      awaitCond( TestStore.recoverCount.get() == 2,Duration("5s"),Duration("0.05s"))
      assert(TestStore.deleted.get() == 1) //We do not delete again, since we have done mark in previous.

      // get its state
      val state2 = aggregateState(recoveredMain2);
      assert(state2 == StringState("WAT"))

      sendDMBlocking(recoveredMain2,OneCmd("END"))
      val state3 = aggregateState(recoveredMain2);
      assert(state3==StringState("END"))

      // make sure we get no msgs
      dest3.expectNoMessage()
    }
  }

}

case class OneCmd(data : String) extends AggregateCmd {
  override def id(): String = ???
}

//This is used in this single test to store state outside of the aggregate across
//actor system restarts
private object TestStore {
  var first = new AtomicBoolean(true)
  var deleted = new AtomicInteger(0)
  var recoverCount = new AtomicInteger(0)
}

class DroppingAggr(dmSelf:ActorPath, dest:ActorPath) extends GeneralAggregateBase[StringEv, StringState](dmSelf) {
  override var state: StringState = StringState("START")

  // Called AFTER event has been applied to state
  override def generateDMs(event: StringEv, previousState: StringState): ResultingDMs = {
    ResultingDMs(List())
  }

  override def cmdToEvent: PartialFunction[AggregateCmd, ResultingEvent[StringEv]] = {
    case x : OneCmd  => ResultingEvent(StringEv(x.data))
  }

  //This simulates dropping this Message
  override def deleteMessages(toSequenceNr : Long) = {
    if(TestStore.first.getAndSet(false)) { //Drop first attempt
      log.info(s"Dropped message for deleting messages up to $toSequenceNr")
    }
    else {
      super.deleteMessages(toSequenceNr)
    }
  }

  override def  onRecoveryCompleted() = {
    super.onRecoveryCompleted()
    TestStore.recoverCount.incrementAndGet()
  }

  override def acceptSnapshotRequest(req: SaveSnapshotOfCurrentState): Boolean = {
    true
  }

  override def onSnapshotSuccess(success: SaveSnapshotSuccess): Unit = {
    log.info(s"Snapshot saving succeeded $success")
  }

  override def onSnapshotFailure(failure: SaveSnapshotFailure): Unit = {
    throw new Exception(failure.cause)
  }

  override def onDeleteMessagesSuccess(success: DeleteMessagesSuccess): Unit = {
    log.info(s"Successfully deleted messages up to ${success.toSequenceNr}")
    TestStore.deleted.incrementAndGet()
  }

  override def onDeleteMessagesFailure(failure: DeleteMessagesFailure): Unit = {
    throw new Exception(failure.cause)
  }

  // Used as prefix/base when constructing the persistenceId to use - the unique ID is extracted runtime from actorPath which is construced by Sharding-coordinator
  override def persistenceIdBase(): String = "/x/"
}
