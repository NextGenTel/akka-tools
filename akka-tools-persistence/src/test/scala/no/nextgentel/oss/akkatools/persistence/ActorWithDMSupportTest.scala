package no.nextgentel.oss.akkatools.persistence

import java.util.concurrent.TimeUnit

import akka.actor.{Props, ActorSystem}
import akka.testkit.{TestProbe, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, Matchers, FunSuiteLike}

import scala.concurrent.duration.FiniteDuration

class ActorWithDMSupportTest(_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {
  def this() = this(ActorSystem("ActorWithDMSupportTest", ConfigFactory.load("application-test.conf")))

  test("success with dm") {
    val a = system.actorOf(Props(new TestActorWithDMSupport()))
    val s = TestProbe()

    // send raw
    s.send(a, "sendok")
    s.expectMsg("ok")

    // send via dm and withNewPayload
    val dm = DurableMessage(1L, "sendok", s.ref.path)
    s.send(a, dm)
    s.expectMsg(dm.withNewPayload("ok"))

    // send raw - do nothing
    s.send(a, "silent")


    // send silent - wait for configm
    s.send(a, DurableMessage(1L, "silent", s.ref.path))
    s.expectMsg( DurableMessageReceived(1,None) )


    // send noconfirm - with dm
    s.send(a, DurableMessage(1L, "no-confirm", s.ref.path))
    s.expectNoMsg(FiniteDuration(500, TimeUnit.MILLISECONDS))

    // send noconfirm - with dm
    s.send(a, DurableMessage(1L, "no-confirm-custom", s.ref.path))
    s.expectNoMsg(FiniteDuration(500, TimeUnit.MILLISECONDS))

    // send noconfirm - without dm
    s.send(a, "no-confirm")
    s.expectNoMsg(FiniteDuration(500, TimeUnit.MILLISECONDS))

    // send noconfirm - without dm
    s.send(a, "no-confirm-custom")
    s.expectNoMsg(FiniteDuration(500, TimeUnit.MILLISECONDS))

  }


}

class TestActorWithDMSupport extends ActorWithDMSupport {
  // All raw messages or payloads in DMs are passed to this function.
  override def receivePayload = {
    case "sendok" =>
      send(sender.path, "ok")
    case "silent" =>
      Unit
    case "no-confirm" =>
      throw new LogWarningAndSkipDMConfirmException("something went wrong")
    case "no-confirm-custom" =>
      throw new CustomLogWarningAndSkipDMConfirm()
  }
}

class CustomLogWarningAndSkipDMConfirm extends Exception with LogWarningAndSkipDMConfirm
