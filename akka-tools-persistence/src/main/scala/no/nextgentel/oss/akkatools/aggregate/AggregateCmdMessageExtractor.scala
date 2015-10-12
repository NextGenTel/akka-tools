package no.nextgentel.oss.akkatools.aggregate

import akka.cluster.sharding.ShardRegion.HashCodeMessageExtractor
import no.nextgentel.oss.akkatools.persistence.{DurableMessage, DurableMessageReceived}
import org.slf4j.LoggerFactory

class AggregateCmdMessageExtractor(val maxNumberOfNodes:Int = 2, val shardsPrNode:Int = 10) extends HashCodeMessageExtractor(maxNumberOfNodes * shardsPrNode) {
  val log = LoggerFactory.getLogger(getClass)

  private def extractId(x:AnyRef):String = {
    x match {
      case a:AggregateCmd =>
        if (a.id == null) {
          log.warn("id() returned null in message: " + x)
        }
        a.id
      case q:AnyRef =>
        log.error("Do not know how to extract entryId for message of type " + x.getClass + ": " + x)
        null
    }
  }

  override def entityId(rawMessage: Any): String = {
    rawMessage match {
      case dm:DurableMessage => extractId(dm.payload)
      case dmr:DurableMessageReceived =>
        dmr.confirmationRoutingInfo.map(_.toString).getOrElse {
          log.warn("DurableMessageReceived.getConfirmationRoutingInfo() returned null in message: " + rawMessage)
          null
        }
      case x:AnyRef => extractId(x)
    }
  }


}