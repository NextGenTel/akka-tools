package no.nextgentel.oss.akkatools.persistence

import akka.actor.{Actor, ActorPath, ActorRef, DiagnosticActorLogging}
import no.nextgentel.oss.akkatools.persistence.DMFunctionExecutorActor.DMFuncMsg

import java.util.concurrent.CompletableFuture
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.runtime.BoxedUnit

// Use this trait to "attach" LogWarningAndSkipDMConfirm to your own exception-type
trait LogWarningAndSkipDMConfirm

// Any regular exception will skip confirming DM, but it will also be handled by
// the actor-system which default to restarting the actor.
// Tho prevent this restarting, and only log a warning,
// you can throw this exception - or extend/implement/with LogWarningAndSkipDMConfirm
class LogWarningAndSkipDMConfirmException(msg:String) extends RuntimeException(msg) with LogWarningAndSkipDMConfirm {
  def this() = this(null)
}

abstract class ActorWithDMSupport extends Actor with DiagnosticActorLogging {

  var pendingDM:Option[DurableMessage] = None

  override def receive: Receive = {
    case dm:DurableMessage =>
      pendingDM = Some(dm)
      try {
        receivePayload.apply(dm.payload)
        // complete the pendingDM if we still have it
        pendingDM.map{ (dm) => dm.confirm(context, self) }
      } catch {
        case e:LogWarningAndSkipDMConfirm =>
          log.warning(e.getMessage + "[dm:notConfirmed]")
        case e:Exception =>
          log.error(e, e.getMessage + "[dm:notConfirmed}")
          throw e
      }

    case msg:Any =>
      try {
        receivePayload.apply(msg)
      } catch {
        case e:LogWarningAndSkipDMConfirm =>
          log.warning(e.getMessage + "[dm:none]")
        case e:Exception =>
          log.error(e, e.getMessage + "[dm:none]")
          throw e
      }
  }

  // All raw messages or payloads in DMs are passed to this function.
  // You can always use send() to send/forward message.
  // If no exception is thrown and the possible incoming DM has not been resent,
  // we will confirm it. If an exception is thrown, an error will be logged and the DM will not be confirmed.
  // To only log warning, you can throw LogWarningAndSkipDMConfirmException/LogWarningAndSkipDMConfirm
  def receivePayload:PartialFunction[Any, Unit]

  // Use this method to send/forward a message.
  // If we have an incoming DM, we will forward the DM withNewPayload.
  // If not, we just send it as it is.
  def send(dest:ActorPath, msg:AnyRef): Unit = {
    pendingDM match {
      case Some(dm) =>
        // send it using the DM
        val msgToSend = dm.withNewPayload(msg)
        context.actorSelection(dest) ! msgToSend
        pendingDM = None
      case None =>
        // send it as plain msg
        context.actorSelection(dest) ! msg
    }


  }
}

abstract class ActorWithDMSupportJava extends ActorWithDMSupport {

  override def receivePayload: PartialFunction[Any, Unit] = {
    case payload: Any => onReceivePayload(payload)
  }

  def onReceivePayload(payload: Any): Unit
}

object MsgResult {
  def create(msg:AnyRef): MsgResult = MsgResult(msg, None) // Java-API
  def create(msg:AnyRef, differentDest: ActorPath): MsgResult = MsgResult(msg, Option(differentDest)) // Java-API
}

case class MsgResult(msg:AnyRef, differentDest:Option[ActorPath] = None)

abstract class ActorWithDMSupportFuture extends Actor with DiagnosticActorLogging {

  implicit val ec:ExecutionContextExecutor = context.dispatcher

  def errorHandling(isProcessingDM:Boolean):PartialFunction[Throwable,Unit] = {

    val dmInfoString:String = if (isProcessingDM) "[dm:notConfirmed]" else "[dm:none]"

    {
      case e: LogWarningAndSkipDMConfirm =>
        log.warning(e.getMessage + dmInfoString)
      case e: Exception =>
        log.error(e, e.getMessage + dmInfoString)
        throw e
    }
  }

  override def receive: Receive = {

    case msg:Any =>

      val dm:Option[DurableMessage] = msg match {
        case x:DurableMessage => Some(x)
        case _ => None
      }

      val payload:Any = dm.map(_.payload).getOrElse(msg)

      val originalDMSender:ActorRef = sender()
      try {
        processPayload.apply(payload).map {
          case Some(MsgResult(resultMsg, Some(differentDest))) =>
            // forward result to different dest
            send(dm, differentDest, resultMsg)
          case Some(MsgResult(resultMsg, None)) =>
            // Send result back to sender
            send(dm, originalDMSender.path, resultMsg)

          case None =>
            // Nothing to send. just confirm DM
            dm.map( x => x.confirm(context, self) )
        }.recover(errorHandling(isProcessingDM = dm.isDefined)  )
      } catch errorHandling(isProcessingDM = dm.isDefined)
  }

  // All raw messages or payloads in DMs are passed to this function.
  // You can optionally send/forward result/msg by returning it
  // If no exception is thrown and the possible incoming DM has not been resent,
  // we will confirm it. If an exception is thrown, an error will be logged and the DM will not be confirmed.
  // To only log warning, you can throw LogWarningAndSkipDMConfirmException/LogWarningAndSkipDMConfirm
  def processPayload:PartialFunction[Any, Future[Option[MsgResult]]]

  // Use this method to send/forward a message.
  // If we have an incoming DM, we will forward the DM withNewPayload.
  // If not, we just send it as it is.
  private def send(dm:Option[DurableMessage], dest:ActorPath, msg:AnyRef): Unit = {
    dm match {
      case Some(dm) =>
        // send it using the DM
        val msgToSend = dm.withNewPayload(msg)
        context.actorSelection(dest) ! msgToSend
      case None =>
        // send it as plain msg
        context.actorSelection(dest) ! msg
    }


  }
}

abstract class ActorWithDMSupportFutureJava extends ActorWithDMSupportFuture {
  import scala.compat.java8.FunctionConverters._

  override def processPayload: PartialFunction[Any, Future[Option[MsgResult]]] = {
    case payload =>
      onProcessPayload(payload).toScala.map( r => Option(r))
  }

  def onProcessPayload(payload: Any): CompletableFuture[MsgResult]
}


object DMFunctionExecutorActor {
  case class DMFuncMsg(event:Any, f:() => Future[Option[AnyRef]])
}

class DMFunctionExecutorActor extends ActorWithDMSupportFuture {

  override def processPayload: PartialFunction[Any, Future[Option[MsgResult]]] = {
    case DMFuncMsg(_, f) => f.apply().map { r =>
      r match {
        case None => None
        case Some(msg) => Some(MsgResult(msg))
      }
    }
  }
}
