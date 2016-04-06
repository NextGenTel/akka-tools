package no.nextgentel.oss.akkatools.aggregate

import akka.actor.ActorPath
import no.nextgentel.oss.akkatools.persistence.SendAsDurableMessage

case class ResultingDurableMessages(list:List[SendAsDurableMessage])

object ResultingDurableMessages {
  def apply(message:AnyRef, destination:ActorPath):ResultingDurableMessages = ResultingDurableMessages(List(SendAsDurableMessage(message, destination)))
  def apply(sendAsDurableMessage: SendAsDurableMessage):ResultingDurableMessages = ResultingDurableMessages(List(sendAsDurableMessage))
}
