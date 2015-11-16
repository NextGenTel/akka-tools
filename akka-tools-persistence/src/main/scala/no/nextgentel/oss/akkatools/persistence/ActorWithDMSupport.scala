package no.nextgentel.oss.akkatools.persistence

import akka.actor.{DiagnosticActorLogging, ActorPath, Actor}

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
