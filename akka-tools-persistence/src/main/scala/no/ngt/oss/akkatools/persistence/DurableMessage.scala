package no.ngt.oss.akkatools.persistence

import akka.actor._
import com.fasterxml.jackson.annotation.JsonTypeInfo
import no.ngt.oss.akkatools.serializing.AkkaJsonSerializable

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
  confirmationRoutingInfo:AnyRef) extends AkkaJsonSerializable {

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
Useful when testing receiving of NgtDurableMessages via TestProbe
 */
class DurableMessageForwardAndConfirm(dest:ActorRef) extends Actor with ActorLogging {

  def receive = {
    case dm:DurableMessage=>
      dest ! dm.payload
      log.debug("durableMessage has been forwarded - confirming it")
      dm.confirm(context, self)
    case x:AnyRef => dest ! x
  }
}

object DurableMessageForwardAndConfirm {
  def apply(dest:ActorRef)(implicit system:ActorSystem):ActorRef = {
    system.actorOf(Props(new DurableMessageForwardAndConfirm(dest)))
  }
}