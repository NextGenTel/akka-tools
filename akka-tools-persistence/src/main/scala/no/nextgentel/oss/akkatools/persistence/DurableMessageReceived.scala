package no.nextgentel.oss.akkatools.persistence

import com.fasterxml.jackson.annotation.JsonTypeInfo
import no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializable

object DurableMessageReceived {
  def apply(deliveryId:Long, confirmationRoutingInfo:AnyRef):DurableMessageReceived = DurableMessageReceived(deliveryId, Option(confirmationRoutingInfo))
}

case class DurableMessageReceived
(
  deliveryId:Long,

  // confirmationRoutingInfo is used as aggregateId when dispatching it to an aggregate using sharding
  @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@confirmationRoutingInfo_class")
  confirmationRoutingInfo:Option[AnyRef]
  ) extends JacksonJsonSerializable