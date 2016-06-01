package no.nextgentel.oss.akkatools.aggregate.v2

import akka.actor.{ ActorPath}
import no.nextgentel.oss.akkatools.aggregate._

import scala.reflect.ClassTag

trait AggregateStateV2Base[E, Config, T <: AggregateStateV2Base[E, Config, T]] extends AggregateState[E, T] {

  def unhandledEventError(event:E):AggregateError = new AggregateError(s"Event is invalid since state is ${getClass.getSimpleName}: $event")

  override def transition(event: E): T = {
    // for some reason, transition.applyOrElse does not work here ...
    if (eventToState.isDefinedAt(event)) {
      eventToState.apply(event)
    } else {
      throw unhandledEventError(event)
    }
  }

  def eventToState:PartialFunction[E, T]

  def cmdToEvent:PartialFunction[(GeneralAggregateV2[E,Config, T], AggregateCmd), ResultingEvent[E]]

  // Called AFTER event has been applied to state
  def generateDMs(config:Config, event:E, previousState:T):ResultingDMs
}

trait AggregateStateV2[E, Config, T <: AggregateStateV2[E, Config,T]] extends AggregateStateV2Base[E, Config, T] {

  // Called AFTER event has been applied to state
  override def generateDMs(config:Config, event: E, previousState: T): ResultingDMs = {
    eventToDMs.applyOrElse((config, event), (t:(Config,E)) => ResultingDMs(List()))
  }

  // Override it if you want to generate DMs
  def eventToDMs:PartialFunction[(Config, E), ResultingDMs]

}

abstract class GeneralAggregateV2[E:ClassTag, Config, S <: AggregateStateV2Base[E, Config, S]:ClassTag](dmSelf:ActorPath)
  extends GeneralAggregateBase[E, S](dmSelf) {

  def config:Config

  override def cmdToEvent: PartialFunction[AggregateCmd, ResultingEvent[E]] = {
    case cmd:AggregateCmd =>
      val defaultCmdToEvent:((GeneralAggregateV2[E,Config, S], AggregateCmd)) => ResultingEvent[E] = { (t) => ResultingEvent(List[E]())} // Do nothing..
      state.cmdToEvent.applyOrElse( (this, cmd), defaultCmdToEvent)
  }

  // Called AFTER event has been applied to state
  override def generateDMs(event: E, previousState: S): ResultingDMs = {
    state.generateDMs( config, event, previousState)
  }

}