package no.nextgentel.oss.akkatools.aggregate

import akka.actor.Props
import no.nextgentel.oss.akkatools.persistence.jdbcjournal.{PersistenceId, PersistenceIdSingle, PersistenceIdSingleTagOnly}
import no.nextgentel.oss.akkatools.persistence.{EnhancedPersistentView, GetState}

import scala.reflect.ClassTag

object GeneralAggregateView {
  // Java-API
  def createProps(
                   persistenceId:PersistenceId,
                   initialState:AggregateStateBaseJava,
                   collectHistory:Boolean = true
                 ): Props = {
    Props(new GeneralAggregateView[AnyRef, AggregateStateBaseJava](persistenceId, initialState, collectHistory))
  }
}

class GeneralAggregateView[E:ClassTag, S <: AggregateStateBase[E, S]:ClassTag]
(
  persistenceId:PersistenceId,
  initialState:S,
  collectHistory:Boolean = true
) extends EnhancedPersistentView[E, S](persistenceId, collectHistory) {

  // Backward-compatible constructor
  def this(persistentIdBase:String, id:String, initialState:S, collectHistory:Boolean) = {
    this(
      if ( id == "*") {
        PersistenceIdSingleTagOnly(persistentIdBase)
      } else {
        PersistenceIdSingle(persistentIdBase, id)
      },
      initialState,
      collectHistory
    )

  }

  var state:S = initialState

  override def currentState():S = state

  override def applyEventToState(event: E): Unit = {
    state = state.transitionState(event).newState
  }

  override val onCmd: PartialFunction[AnyRef, Unit] = {
    case x:GetState =>
      sender() ! state
  }
}
