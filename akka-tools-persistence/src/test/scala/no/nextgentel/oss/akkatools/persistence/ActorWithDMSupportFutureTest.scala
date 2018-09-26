package no.nextgentel.oss.akkatools.persistence

import java.util.concurrent.TimeUnit

import akka.actor.{ActorPath, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class ActorWithDMSupportFutureTest(_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {
  def this() = this(ActorSystem("ActorWithDMSupportFutureTest", ConfigFactory.load("application-test.conf")))

  test("success with dm") {
    val a = system.actorOf(Props(new TestActorWithDMSupportFuture()))
    val s = TestProbe()
    val d = TestProbe()

    // send raw
    s.send(a, "sendok")
    s.expectMsg("ok")

    // send raw - different dest
    s.send(a, d.ref.path)
    d.expectMsg("ok")

    // send via dm and withNewPayload
    {
      val dm = DurableMessage(1L, "sendok", s.ref.path)
      s.send(a, dm)
      s.expectMsg(dm.withNewPayload("ok"))
    }

    // send via dm and withNewPayload - different dest
    {
      val dm = DurableMessage(1L, d.ref.path, s.ref.path)
      s.send(a, dm)
      d.expectMsg(dm.withNewPayload("ok"))
    }

    // send raw - do nothing
    s.send(a, "silent")


    // send silent - wait for configm
    s.send(a, DurableMessage(1L, "silent", s.ref.path))
    s.expectMsg( DurableMessageReceived(1,None) )


    // send noconfirm - with dm
    s.send(a, DurableMessage(1L, "no-confirm", s.ref.path))
    s.expectNoMessage(FiniteDuration(500, TimeUnit.MILLISECONDS))

    // send noconfirm future - with dm
    s.send(a, DurableMessage(1L, "no-confirm-future", s.ref.path))
    s.expectNoMessage(FiniteDuration(500, TimeUnit.MILLISECONDS))

    // send noconfirm - with dm
    s.send(a, DurableMessage(1L, "no-confirm-custom", s.ref.path))
    s.expectNoMessage(FiniteDuration(500, TimeUnit.MILLISECONDS))

    // send noconfirm future - with dm
    s.send(a, DurableMessage(1L, "no-confirm-custom-future", s.ref.path))
    s.expectNoMessage(FiniteDuration(500, TimeUnit.MILLISECONDS))

    // send noconfirm - without dm
    s.send(a, "no-confirm")
    s.expectNoMessage(FiniteDuration(500, TimeUnit.MILLISECONDS))

    // send noconfirm future - without dm
    s.send(a, "no-confirm-future")
    s.expectNoMessage(FiniteDuration(500, TimeUnit.MILLISECONDS))

    // send noconfirm - without dm
    s.send(a, "no-confirm-custom")
    s.expectNoMessage(FiniteDuration(500, TimeUnit.MILLISECONDS))

    // send noconfirm future - without dm
    s.send(a, "no-confirm-custom-future")
    s.expectNoMessage(FiniteDuration(500, TimeUnit.MILLISECONDS))

  }


}

class TestActorWithDMSupportFuture extends ActorWithDMSupportFuture {
  // All raw messages or payloads in DMs are passed to this function.
  override def processPayload = {
    case "sendok" =>
      Future {
        Some(MsgResult("ok"))
      }

    case differentDest:ActorPath =>
      Future {
        Some(MsgResult("ok", Some(differentDest)))
      }

    case "silent" =>
      Future {
        None
      }

    case "no-confirm" =>
      throw new LogWarningAndSkipDMConfirmException("something went wrong")

    case "no-confirm-future" =>
      Future {
        throw new LogWarningAndSkipDMConfirmException("something went wrong")
      }

    case "no-confirm-custom" =>
      throw new CustomLogWarningAndSkipDMConfirm()

    case "no-confirm-custom-future" =>
      Future {
        throw new CustomLogWarningAndSkipDMConfirm()
      }
  }
}

