package no.nextgentel.oss.akkatools.aggregate

import no.nextgentel.oss.akkatools.persistence.InternalCommand

case class SaveSnapshotOfCurrentState(dispatchId:Option[String],deleteEvents : Boolean) extends AggregateCmd with InternalCommand {
  override def id(): String = dispatchId.getOrElse(throw new RuntimeException("This SaveSnapshotOfCurrentState does not have a dispatch-id"))
}

case class AggregateRejectedSnapshotRequest(persistenceId: String, sequenceNr: Long, stateWhenRejected: Any)