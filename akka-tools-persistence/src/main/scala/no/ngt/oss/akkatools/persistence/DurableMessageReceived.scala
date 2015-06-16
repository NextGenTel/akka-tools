package no.ngt.oss.akkatools.persistence

import com.fasterxml.jackson.annotation.JsonTypeInfo
import no.ngt.oss.akkatools.serializing.AkkaJsonSerializable

object DurableMessageReceived {
  def apply(deliveryId:Long, confirmationRoutingInfo:AnyRef):DurableMessageReceived = DurableMessageReceived(deliveryId, Option(confirmationRoutingInfo))
}

case class DurableMessageReceived
(
  deliveryId:Long,

  @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@confirmationRoutingInfo_class")
  confirmationRoutingInfo:Option[AnyRef]
  ) extends AkkaJsonSerializable