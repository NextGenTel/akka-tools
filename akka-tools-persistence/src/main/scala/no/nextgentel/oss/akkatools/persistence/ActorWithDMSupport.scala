package no.nextgentel.oss.akkatools.persistence

import akka.actor.{ActorPath, Actor}

abstract class ActorWithDMSupport extends Actor{

  var pendingDM:Option[DurableMessage] = None

  override def receive: Receive = {
    case dm:DurableMessage =>
      pendingDM = Some(dm)
      val success = internalReceive(dm.payload)
      if (success) {
        // complete the pendingDM if we still have it
        pendingDM.map{ (dm) => dm.confirm(context, self) }
      }

    case msg:Any =>
      internalReceive(msg)
  }

  // return success if we should confirm the (unused) dm
  def internalReceive:PartialFunction[Any, Boolean]

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
