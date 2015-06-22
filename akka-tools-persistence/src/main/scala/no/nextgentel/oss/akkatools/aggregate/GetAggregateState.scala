package no.nextgentel.oss.akkatools.aggregate

object GetAggregateState {
  def apply():GetAggregateState = GetAggregateState(None)
  def apply(dispatchId:String):GetAggregateState = GetAggregateState(Some(dispatchId))
}

case class GetAggregateState(dispatchId:Option[String]) extends AggregateCmd {
  override def id(): String = dispatchId.getOrElse(throw new RuntimeException("This GetAggregateState does not have a dispatch-id"))
}
