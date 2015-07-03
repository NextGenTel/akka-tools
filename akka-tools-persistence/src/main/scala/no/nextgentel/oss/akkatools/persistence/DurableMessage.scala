package no.nextgentel.oss.akkatools.persistence

import akka.actor._
import com.fasterxml.jackson.annotation.JsonTypeInfo
import no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializable

object DurableMessage {
  def apply(deliveryId:Long, payload:AnyRef, sender:String):DurableMessage = DurableMessage(deliveryId, payload, sender, null)
  def apply(deliveryId:Long, payload:AnyRef, sender:ActorPath):DurableMessage = DurableMessage(deliveryId, payload, sender.toString, null)
  def apply(deliveryId:Long, payload:AnyRef, sender:ActorPath, confirmationRoutingInfo:AnyRef):DurableMessage = DurableMessage(deliveryId, payload, sender.toString, confirmationRoutingInfo)
}

case class DurableMessage
(
  deliveryId:Long,
  @JsonTypeInfo(use=JsonTypeInfo.Id.CLASS, include= JsonTypeInfo.As.PROPERTY, property="@payload_class")
  payload:AnyRef,
  sender:String,
  @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@confirmationRoutingInfo_class")
  confirmationRoutingInfo:AnyRef) extends JacksonJsonSerializable {

  def withNewPayload(newPayload:AnyRef):DurableMessage = copy(payload = newPayload)

  def confirm(systemOrContext:ActorRefFactory, confirmingActor:ActorRef):Unit = {
    if (sender != null) {
      systemOrContext.actorSelection(sender).tell(DurableMessageReceived.apply(deliveryId, confirmationRoutingInfo), confirmingActor);
    }
  }

  def payloadAs[T]():T = {
    payload.asInstanceOf[T]
  }

}


/*
Useful when testing receiving of DurableMessages via TestProbe
 */
class DurableMessageForwardAndConfirm(dest:ActorRef, onlyAcceptDurableMessages:Boolean) extends Actor with ActorLogging {

  def receive = {
    case dm:DurableMessage=>
      dest.forward(dm.payload)
      log.debug("durableMessage has been forwarded - confirming it")
      dm.confirm(context, self)
    case x:AnyRef =>
      if (onlyAcceptDurableMessages) {
        log.error("Expecting durableMessage to forward but got this message instead: " + x)
      } else {
        log.debug("Forwarding none-durableMessage")
        dest.forward(x)
      }

  }
}

object DurableMessageForwardAndConfirm {
  def apply(dest:ActorRef, onlyAcceptDurableMessages:Boolean = false)(implicit system:ActorSystem):ActorRef = {
    system.actorOf(Props(new DurableMessageForwardAndConfirm(dest, onlyAcceptDurableMessages)))
  }
}