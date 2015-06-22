package no.nextgentel.oss.akkatools.persistence

import no.nextgentel.oss.akkatools.aggregate.AggregateCmd

object GetState {
  def apply():GetState = GetState(None)
  def apply(dispatchId:String):GetState = GetState(Some(dispatchId))
}

case class GetState(dispatchId:Option[String]) extends AggregateCmd {
  override def id(): String = dispatchId.getOrElse(throw new RuntimeException("This GetState does not have a dispatch-id"))
}
