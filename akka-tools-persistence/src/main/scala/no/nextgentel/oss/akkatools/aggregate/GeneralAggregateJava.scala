package no.nextgentel.oss.akkatools.aggregate

import java.util.function.Consumer

import akka.actor.{ActorSystem, ActorPath}
import java.util.{List => JList}
import scala.collection.JavaConversions._
import no.nextgentel.oss.akkatools.persistence.EnhancedPersistentActor

import scala.concurrent.duration.FiniteDuration


trait AggregateStateJava extends AggregateState[Any, AggregateStateJava]

abstract class GeneralAggregateJava[S]
  (
    initialState:AggregateStateJava,
    ourDispatcherActor:ActorPath
  ) extends GeneralAggregate[Any, AggregateStateJava](ourDispatcherActor) { ////(scala.reflect.ClassTag.apply(eventClass), scala.reflect.ClassTag.apply(stateClass))

  var state:AggregateStateJava = initialState

  def getState():S = state.asInstanceOf[S]

  def onCmdToEvent(cmd:AggregateCmd):ResultingEventJava
  def onGenerateResultingDurableMessages(event:Any):ResultingDurableMessages

  override def cmdToEvent: PartialFunction[AggregateCmd, ResultingEvent[Any]] = {
    case cmd:AnyRef => onCmdToEvent(cmd).asResultingEvent()
  }

  override def generateResultingDurableMessages: PartialFunction[Any, ResultingDurableMessages] = {
    case e:Any => onGenerateResultingDurableMessages(e)
  }

}

object ResultingEventJava {
  def list(events:JList[Any]):ResultingEventJava = new ResultingEventJava(events, null, null, null)
  def single(event:Any):ResultingEventJava = new ResultingEventJava(Seq(event), null, null, null)
  def empty():ResultingEventJava = new ResultingEventJava(Seq(), null, null, null)
}

case class ResultingEventJava(events:JList[Any], successHandler: Runnable, afterValidationSuccessHandler:Runnable, errorHandler:Consumer[String]) {

  def onError(errorHandler:Consumer[String]) = copy( errorHandler = errorHandler)

  // Called after a valid event is persisted
  def onSuccess(handler: Runnable) = copy( successHandler = handler)

  // Called after event is validated as success but before it is persisted
  def onAfterValidationSuccess(handler: => Runnable) = copy(afterValidationSuccessHandler = handler)


  def asResultingEvent():ResultingEvent[Any] = {
    ResultingEvent(
    events.toList,
    Option(errorHandler).map( h => (e:String) => h.accept(e)).getOrElse(null),
    Option(successHandler).map( h => () => h.run()).getOrElse(null),
    Option(afterValidationSuccessHandler).map(h => () => h.run()).getOrElse(null)
    )
  }

}

class GeneralAggregateViewJava
(
  persistenceIdBase:String,
  id:String,
  initialState:AggregateStateJava,
  collectHistory:Boolean = true
) extends GeneralAggregateView[Any, AggregateStateJava](persistenceIdBase, id, initialState, collectHistory) {

}

@deprecated("Use AggregateStarter and AggregateViewStarter with GeneralAggregateViewJava instead", "1.0.3")
class GeneralAggregateBuilderJava
(
   actorSystem: ActorSystem
) extends GeneralAggregateBuilder[Any, AggregateStateJava](actorSystem) {

}