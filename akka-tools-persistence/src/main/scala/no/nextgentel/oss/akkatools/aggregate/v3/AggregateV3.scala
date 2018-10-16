package no.nextgentel.oss.akkatools.aggregate.v3

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorPath, ActorRef, ActorSystem, Props}
import no.nextgentel.oss.akkatools.aggregate._
import no.nextgentel.oss.akkatools.aggregate.v3.GeneralAggregateV3.props
import no.nextgentel.oss.akkatools.persistence.DMFunctionExecutorActor.DMFuncMsg
import no.nextgentel.oss.akkatools.persistence.{DMFunctionExecutorActor, SendAsDM}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

trait AggregateStateV3Base[E, Config, T <: AggregateStateV3Base[E, Config, T]] extends AggregateStateBase[E, T] {

  def unhandledEventError(event:E):AggregateError = new AggregateError(s"Event is invalid since state is ${getClass.getSimpleName}: $event")

  override def transitionState(event: E): StateTransition[E,T] = {
    // for some reason, transition.applyOrElse does not work here ...
    if (eventToState.isDefinedAt(event)) {
      eventToState.apply(event)
    } else {
      throw unhandledEventError(event)
    }
  }

  def eventToState:PartialFunction[E, StateTransition[E,T]]

  def cmdToEvent(aggregate:GeneralAggregateV3[E,Config, T]):PartialFunction[AggregateCmd, ResultingEvent[E]]

  // Called AFTER event has been applied to state
  def generateDMs(aggregate:GeneralAggregateV3[E,Config, T], config:Config, event:E, previousState:T):ResultingDMs

  def generateEventsForFailedDM(originalPayload: Any, errorMsg: String): Seq[E] = Seq()
}

trait AggregateStateV3[E, Config, T <: AggregateStateV3[E, Config,T]] extends AggregateStateV3Base[E, Config, T] {

  // Called AFTER event has been applied to state
  override def generateDMs(aggregate:GeneralAggregateV3[E,Config, T], config:Config, event: E, previousState: T): ResultingDMs = {
    eventToDMs(config).applyOrElse(event, (t:E) => ResultingDMs(List()))
  }

  // Override it if you want to generate DMs
  def eventToDMs(config:Config):PartialFunction[E, ResultingDMs]

}

trait AggregateStateV3WithDMFunc[E, Config, T <: AggregateStateV3WithDMFunc[E, Config,T]] extends AggregateStateV3Base[E, Config, T] {

  type EventToDMFuncArgs = (Config, E)
  type DMFunc = () => Future[Option[AnyRef]]

  // Called AFTER event has been applied to state
  override def generateDMs(aggregate:GeneralAggregateV3[E,Config, T], config:Config, event: E, previousState: T): ResultingDMs = {

    val f = eventToDMFunc(config, aggregate.ec)

    if ( f.isDefinedAt(event)) {
      val dmFunc = f.apply(event)

      val dmFuncMsg = DMFuncMsg(event, dmFunc)

      // Send dmFunc to DMFunctionExecutorActor
      ResultingDMs(SendAsDM(dmFuncMsg, aggregate.dmFunctionExecutorActor.path))

    } else {
      // Nothing to do
      ResultingDMs(List())
    }

  }

  // Override it if you want to generate DMs
  def eventToDMFunc(config:Config, ec:ExecutionContext):PartialFunction[E, DMFunc] = Map.empty

}

object GeneralAggregateV3Starter {
  val nextStarterNameId = new AtomicInteger(0)
}

class GeneralAggregateV3Starter[E:ClassTag, Config, S <: AggregateStateV3Base[E, Config, S]:ClassTag]
(
  actorSystem: ActorSystem,
  name: String,
  initialState:S,
  allowMultipleStartersForTesting:Boolean = false
) {

  import GeneralAggregateV3Starter._

  // Start dmFunctionExecutorActor
  private val dmFunctionExecutorActor = actorSystem.actorOf(Props(new DMFunctionExecutorActor))

  val persistenceIdBase = name + "/"


  val starterName:String = if ( allowMultipleStartersForTesting ) {
    name + "-" + nextStarterNameId.incrementAndGet()
  } else {
    name
  }


  private val starter = AggregateStarterSimple(starterName, actorSystem)

  def dispatcher:ActorRef = starter.dispatcher

  def start
  (
    config:Config
  ):Unit = {
      starter.withAggregatePropsCreator {
        dmSelf =>
          props[E, Config, S](
            dmSelf,
            dmFunctionExecutorActor,
            persistenceIdBase,
            initialState,
            config
          )
      }.start()

  }


}


object GeneralAggregateV3 {

  def props[E:ClassTag, Config, S <: AggregateStateV3Base[E, Config, S]:ClassTag]
  (
    dmSelf: ActorPath,
    dmFunctionExecutorActor: ActorRef,
    _persistenceIdBase: String,
    initialState: S,
    config: Config
  ): Props = {
    Props( new GeneralAggregateV3[E, Config, S](
      dmSelf,
      dmFunctionExecutorActor,
      _persistenceIdBase,
      initialState,
      config
    ))
  }
}

class GeneralAggregateV3[E:ClassTag, Config, S <: AggregateStateV3Base[E, Config, S]:ClassTag]
(
  dmSelf:ActorPath,
  val dmFunctionExecutorActor:ActorRef,
  _persistenceIdBase:String,
  initialState:S,
  val config:Config
)
  extends GeneralAggregateBase[E, S](dmSelf) {


  override var state: S = initialState

  override def persistenceIdBase(): String = _persistenceIdBase

  override def cmdToEvent: PartialFunction[AggregateCmd, ResultingEvent[E]] = {
    case cmd:AggregateCmd =>
      val defaultCmdToEvent:(AggregateCmd) => ResultingEvent[E] = { (t) => ResultingEvent(List[E]())} // Do nothing..
      state.cmdToEvent(this).applyOrElse( cmd, defaultCmdToEvent)
  }

  // Called AFTER event has been applied to state
  override def generateDMs(event: E, previousState: S): ResultingDMs = {
    state.generateDMs( this, config, event, previousState)
  }

  // Sends a cmd/dm to the dispatcher og this aggregate
  def sendToAggregate(cmd:Any): Unit = {
    context.actorSelection(dmSelf).tell(cmd, ActorRef.noSender)
  }

  /**
    * Override this to decide if the failed outbound durableMessage should result in a persisted event.
    * If so, return these events.
    * When these have been persisted, generateDMs() will be called as usual enabling
    * you to perform some outbound action.
    *
    * @param originalPayload
    * @param errorMsg
    * @return
    */
  override def generateEventsForFailedDurableMessage(originalPayload: Any, errorMsg: String): Seq[E] = {
    state.generateEventsForFailedDM(originalPayload, errorMsg)
  }
}
