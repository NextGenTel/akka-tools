package no.nextgentel.oss.akkatools.aggregate

import scala.reflect.ClassTag


object StateTransition {
  //def apply[E, S <: AggregateStateBase[E,S]](newState:S):StateTransition[E,S] = StateTransition[E,S](newState, None)
  //def apply[E, S <: AggregateStateBase[E,S]](newState:S, newEvent:E):StateTransition[E,S] = StateTransition[E,S](newState, Some(newEvent))
}


case class StateTransition[E, S <: AggregateStateBase[E,S]]
(
  newState:S,
  newEvent:Option[E] = None
) {
  //def withNewEvent(newEvent:E):StateTransition[E,S] = copy(newEvent = Some(newEvent))
}


trait AggregateStateBase[E, S <: AggregateStateBase[E,S]] {
  def transitionState(event:E):StateTransition[E,S]
}

trait AggregateState[E, S <: AggregateStateBase[E,S]] extends AggregateStateBase[E,S] {




//  override def transitionState[U >: E](event: U): StateTransition[U, S] = {
//    StateTransition[U,S](transition(event), None)
//  }

  override def transitionState(event: E): StateTransition[E, S] = StateTransition[E,S](transition(event), None)

  def transition(event:E):S

}
