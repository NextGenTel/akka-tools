package no.nextgentel.oss.akkatools.aggregate.aggregateTest_usingAggregateStateBase

import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.actor.{ActorPath, ActorSystem, Props}
import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotMetadata, SnapshotOffer}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import no.nextgentel.oss.akkatools.aggregate.{AggregateCmd, _}
import no.nextgentel.oss.akkatools.testing.{AggregateStateGetter, AggregateTesting}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers}
import org.slf4j.LoggerFactory


/**
  * Testing that internal snapshot related messages are handled
  */
class GeneralAggregateBaseTest_dropDeleteMessagesTest(_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {

  def this() = this(ActorSystem("test-actor-system", ConfigFactory.load("application-test.conf")))

  override def afterAll {
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

  }

  test("test that snapshot handling messages triggers handling") {
    new TestEnv {
      assertState(StringState("START"))

      sendDMBlocking(main,OneCmd("WAT"))
      assertState(StringState("WAT"))

      sendDMBlocking(main,SaveSnapshotOfCurrentState(None,true))
      assertState(StringState("WAT"))

      Thread.sleep(2000)
      // kill it
      system.stop(main)

      // Wait for it to die
      Thread.sleep(2000)

      // recreate it
      val dest2 = TestProbe()
      val recoveredMain = system.actorOf(Props( new DroppingAggr(null, dmForwardAndConfirm(dest2.ref).path)), "DroppingAggr-" + id)

      Thread.sleep(2000)
      // get its state
      val recoveredState = AggregateStateGetter[Any](recoveredMain).getState(None).asInstanceOf[StringState]
      assert( recoveredState == StringState("WAT"))
      assert(DropStore.deleted.get() == 1)

      // make sure we get no msgs
      dest2.expectNoMsg()

      Thread.sleep(2000)
      // kill it
      system.stop(recoveredMain)
      // Wait for it to die
      Thread.sleep(2000)

      // recreate it
      val dest3 = TestProbe()
      val recoveredMain2 = system.actorOf(Props( new DroppingAggr(null, dmForwardAndConfirm(dest3.ref).path)), "DroppingAggr-" + id)

      Thread.sleep(2000)
      // get its state
      val recoveredState2 = AggregateStateGetter[Any](recoveredMain2).getState(None).asInstanceOf[StringState]
      assert( recoveredState2 == StringState("WAT")) //State is presernved
      assert(DropStore.deleted.get() == 1) //We do not delete again, so we have done mark in previous.

      // make sure we get no msgs
      dest3.expectNoMsg()
    }
  }

}

case class OneCmd(data : String) extends AggregateCmd {
  override def id(): String = ???
}

//Needs somewhere to communicate state outside of actor, this is hacky, but works.
object DropStore {
  var first = new AtomicBoolean(true)
  var deleted = new AtomicInteger(0)
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
    if(DropStore.first.getAndSet(false)) { //Drop first attempt
      log.info(s"Dropped message for deleting messages up to $toSequenceNr")
    }
    else {
      super.deleteMessages(toSequenceNr)
    }
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
    DropStore.deleted.incrementAndGet()
  }

  override def onDeleteMessagesFailure(failure: DeleteMessagesFailure): Unit = {
    throw new Exception(failure.cause)
  }

  // Used as prefix/base when constructing the persistenceId to use - the unique ID is extracted runtime from actorPath which is construced by Sharding-coordinator
  override def persistenceIdBase(): String = "/x/"
}
