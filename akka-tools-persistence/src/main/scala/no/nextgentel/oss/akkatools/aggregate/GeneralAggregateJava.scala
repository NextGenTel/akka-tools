package no.nextgentel.oss.akkatools.aggregate

import akka.actor.ActorPath
import no.nextgentel.oss.akkatools.persistence.EnhancedPersistentActor

import scala.concurrent.duration.FiniteDuration


trait AggregateStateJava extends AggregateState[Any, AggregateStateJava]

abstract class GeneralAggregateJava
  (
    initialState:AggregateStateJava,
    ourDispatcherActor:ActorPath
  ) extends GeneralAggregate[Any, AggregateStateJava](ourDispatcherActor) { ////(scala.reflect.ClassTag.apply(eventClass), scala.reflect.ClassTag.apply(stateClass))

  var state:AggregateStateJava = initialState

  def onCmdToEvent(cmd:AggregateCmd):ResultingEvent[Any]
  def onGenerateResultingDurableMessages(event:Any):ResultingDurableMessages

  override def cmdToEvent: PartialFunction[AggregateCmd, ResultingEvent[Any]] = {
    case cmd:AnyRef => onCmdToEvent(cmd)
  }

  override def generateResultingDurableMessages: PartialFunction[Any, ResultingDurableMessages] = {
    case e:Any => onGenerateResultingDurableMessages(e)
  }

}