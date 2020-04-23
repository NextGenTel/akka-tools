package no.nextgentel.oss.akkatools.aggregate

import no.nextgentel.oss.akkatools.persistence.InternalCommand

case class SaveSnapshotOfCurrentState(dispatchId:Option[String]) extends AggregateCmd with InternalCommand {
  override def id(): String = dispatchId.getOrElse(throw new RuntimeException("This GetState does not have a dispatch-id"))
}
