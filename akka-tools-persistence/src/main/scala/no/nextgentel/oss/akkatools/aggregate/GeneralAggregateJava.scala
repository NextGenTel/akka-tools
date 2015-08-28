package no.nextgentel.oss.akkatools.aggregate

import akka.actor.ActorPath
import no.nextgentel.oss.akkatools.persistence.EnhancedPersistentActor

import scala.concurrent.duration.FiniteDuration


trait AggregateStateJava extends AggregateState[Any, AggregateStateJava]

abstract class GeneralAggregateJava
  (
    initialState:AggregateStateJava,
    idleTimeout:FiniteDuration,
    ourDispatcherActor:ActorPath
  ) extends GeneralAggregate[Any, AggregateStateJava](idleTimeout, ourDispatcherActor) { ////(scala.reflect.ClassTag.apply(eventClass), scala.reflect.ClassTag.apply(stateClass))

  def this(
    initialState:AggregateStateJava,
    ourDispatcherActor:ActorPath) = this(initialState, EnhancedPersistentActor.DEFAULT_IDLE_TIMEOUT_IN_SECONDS, ourDispatcherActor)

  var state:AggregateStateJava = initialState

  def onCmdToEvent(cmd:AnyRef):ResultingEvent[Any]
  def onGenerateResultingDurableMessages(event:Any):ResultingDurableMessages

  override def cmdToEvent: PartialFunction[AnyRef, ResultingEvent[Any]] = {
    case cmd:AnyRef => onCmdToEvent(cmd)
  }

  override def generateResultingDurableMessages: PartialFunction[Any, ResultingDurableMessages] = {
    case e:Any => onGenerateResultingDurableMessages(e)
  }

}