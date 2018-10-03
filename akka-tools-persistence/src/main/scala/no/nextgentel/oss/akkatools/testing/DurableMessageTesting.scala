package no.nextgentel.oss.akkatools.testing

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor._
import no.nextgentel.oss.akkatools.persistence.{DurableMessageForwardAndConfirm, DurableMessage, DurableMessageReceived}
import no.nextgentel.oss.akkatools.testing.TestingDurableMessageSendAndReceiver.Timeout

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future, Promise}
import scala.reflect.ClassTag

object DurableMessageTesting extends DurableMessageTesting {

}

trait DurableMessageTesting {

  private def resolveTimeout(timeout:FiniteDuration, system:ActorSystem): FiniteDuration = {
    if ( timeout != null ) {
      timeout
    } else if (system.settings.config.hasPath("akka.test.single-expect-default")) {
      val l:Long = system.settings.config.getDuration("akka.test.single-expect-default", TimeUnit.MILLISECONDS)
      FiniteDuration(l, TimeUnit.MILLISECONDS)
    } else {
      FiniteDuration(3, TimeUnit.SECONDS)
    }
  }

  def sendDM(dest:ActorRef, payload:AnyRef, sender:ActorRef = ActorRef.noSender, timeout:FiniteDuration = null)(implicit system:ActorSystem):DurableMessageConfirmationChecker = {

    val timeoutToUse = resolveTimeout(timeout, system)

    val resultPromise = Promise[Boolean]
    val messageSentPromise = Promise[Unit]
    val receiver = system.actorOf(Props(new TestingDurableMessageSendAndReceiver(resultPromise, dest, payload, sender, timeoutToUse, messageSentPromise)))

    // Waiting for the message being sent
    Await.result(messageSentPromise.future, timeoutToUse)

    new DurableMessageConfirmationChecker(resultPromise.future, timeoutToUse)
  }

  def sendDMBlocking(dest:ActorRef, payload:AnyRef, sender:ActorRef = ActorRef.noSender, timeout:FiniteDuration = null)(implicit system:ActorSystem): Unit = {
    val timeoutToUse = resolveTimeout(timeout, system)
    sendDM(dest, payload, sender, timeoutToUse).isConfirmed()
  }

}

class DurableMessageConfirmationChecker(futureResult:Future[Boolean], timeout:FiniteDuration) {

  def isConfirmed()(implicit system:ActorSystem):Boolean = {
    implicit val ec = system.dispatcher
    Await.result(futureResult, timeout)
  }
}



object TestingDurableMessageSendAndReceiver {
  case class Timeout()
}

class TestingDurableMessageSendAndReceiver private [testing] (promise:Promise[Boolean], dest:ActorRef, payload:AnyRef, sender:ActorRef, timeout:FiniteDuration, messageSentPromise:Promise[Unit]) extends Actor with ActorLogging {


  val messageId = UUID.randomUUID().toString
  implicit val ec = context.dispatcher
  val timer = context.system.scheduler.scheduleOnce(timeout, self, Timeout())

  log.debug(s"Sending durableMessage with payload=$payload with messageId=$messageId to dest=$dest")

  dest.tell(DurableMessage(0L, payload, self.path), sender)
  messageSentPromise.success(Unit)


  def receive = {
    case Timeout() =>
      val errorMsg = s"Timeout out waiting for confirmation of TestingDurableMessage with messageId=$messageId"
      log.error(errorMsg)
      promise.failure(new Exception(errorMsg))
      context.stop(self)
    case x:DurableMessageReceived =>
      log.debug(s"Got confirmation for DurableMessage with messageId=$messageId")
      promise.success(true)
      timer.cancel()
      context.stop(self)
    case x:AnyRef =>
      log.warning(s"Received something else while waiting for messageId=$messageId: " + x)
  }
}


trait AggregateTesting[S] extends DurableMessageTesting {

  val main:ActorRef

  def getState()(implicit system:ActorSystem):S = AggregateStateGetter[Any](main).getState(None).asInstanceOf[S]
  def getState(aggregateId:String)(implicit system:ActorSystem):S = AggregateStateGetter[Any](main).getState(Some(aggregateId)).asInstanceOf[S]

  def dmForwardAndConfirm(dest:ActorRef, onlyAcceptDurableMessages:Boolean = false)(implicit system:ActorSystem) = DurableMessageForwardAndConfirm(dest, onlyAcceptDurableMessages)
}
