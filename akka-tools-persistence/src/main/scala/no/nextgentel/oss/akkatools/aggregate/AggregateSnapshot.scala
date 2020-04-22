package no.nextgentel.oss.akkatools.aggregate

import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer}
import no.nextgentel.oss.akkatools.persistence.InternalCommand


case class SaveSnapshotOfCurrentState(dispatchId:Option[String]) extends AggregateCmd with InternalCommand {
  override def id(): String = dispatchId.getOrElse(throw new RuntimeException("This GetState does not have a dispatch-id"))
}

/**
 * Hooks for handling snapshots of aggregates
 */
trait AggregatePersistenceHandler {

  /**
   * Must only recover the state, should not do anything that can fail
   */
  val onSnapshotOffer : PartialFunction[SnapshotOffer,Unit]

  /**
   * return true if the request to make a snapshot is accepted
   */
  val acceptSnapshotRequest : PartialFunction[SaveSnapshotOfCurrentState,Boolean]

  val onSnapshotSuccess: PartialFunction[SaveSnapshotSuccess, Unit]
  val onSnapshotFailure: PartialFunction[SaveSnapshotFailure, Unit]
  val onDeleteMessagesSuccess: PartialFunction[DeleteMessagesSuccess, Unit]
  val onDeleteMessagesFailure: PartialFunction[DeleteMessagesFailure, Unit]
}
