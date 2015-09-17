package no.nextgentel.oss.akkatools.testing

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor._
import no.nextgentel.oss.akkatools.persistence.{DurableMessageForwardAndConfirm, DurableMessage, DurableMessageReceived}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future, Promise}
import scala.reflect.ClassTag

object DurableMessageTesting extends DurableMessageTesting {
  val defaultTimeout = FiniteDuration(3, TimeUnit.SECONDS)

}

trait DurableMessageTesting {
  import DurableMessageTesting.defaultTimeout

  def sendDM(dest:ActorRef, payload:AnyRef, sender:ActorRef = ActorRef.noSender, timeout:FiniteDuration = defaultTimeout)(implicit system:ActorSystem):DurableMessageConfirmationChecker = {

    val resultPromise = Promise[Boolean]
    val messageSentPromise = Promise[Unit]
    val receiver = system.actorOf(Props(new TestingDurableMessageSendAndReceiver(resultPromise, dest, payload, sender, timeout, messageSentPromise)))

    // Waiting for the message being sent
    Await.result(messageSentPromise.future, timeout)

    new DurableMessageConfirmationChecker(resultPromise.future, timeout)
  }

  def sendDMBlocking(dest:ActorRef, payload:AnyRef, sender:ActorRef = ActorRef.noSender, timeout:FiniteDuration = defaultTimeout)(implicit system:ActorSystem): Unit = {
    sendDM(dest, payload, sender, timeout).isConfirmed()
  }

}

class DurableMessageConfirmationChecker(futureResult:Future[Boolean], timeout:FiniteDuration) {

  def isConfirmed()(implicit system:ActorSystem):Boolean = {
    implicit val ec = system.dispatcher
    Await.result(futureResult, timeout)
  }
}



class TestingDurableMessageSendAndReceiver private [testing] (promise:Promise[Boolean], dest:ActorRef, payload:AnyRef, sender:ActorRef, timeout:FiniteDuration, messageSentPromise:Promise[Unit]) extends Actor with ActorLogging {

  case class Timeout()
  val messageId = UUID.randomUUID().toString
  implicit val ec = context.dispatcher
  val timer = context.system.scheduler.scheduleOnce(timeout, self, Timeout())

  log.debug(s"Sending durableMessage with payload=$payload with messageId=$messageId to dest=$dest")

  dest.tell(DurableMessage(0L, payload, self.path), sender)
  messageSentPromise.success()


  def receive = {
    case Timeout() =>
      val errorMsg = s"Timeout out waiting for confirmation of TestingDurableMessage with messageId=$messageId"
      log.error(errorMsg)
      promise.failure(new Exception(errorMsg))
    case x:DurableMessageReceived =>
      log.debug(s"Got confirmation for DurableMessage with messageId=$messageId")
      promise.success(true)
      timer.cancel()
    case x:AnyRef =>
      log.warning(s"Received something else while waiting for messageId=$messageId: " + x)
  }
}


trait AggregateTesting[S] extends DurableMessageTesting {

  val main:ActorRef

  def getState()(implicit system:ActorSystem):S = AggregateStateGetter[Any](main).getState().asInstanceOf[S]

  def dmForwardAndConfirm(dest:ActorRef, onlyAcceptDurableMessages:Boolean = false)(implicit system:ActorSystem) = DurableMessageForwardAndConfirm(dest, onlyAcceptDurableMessages)
}