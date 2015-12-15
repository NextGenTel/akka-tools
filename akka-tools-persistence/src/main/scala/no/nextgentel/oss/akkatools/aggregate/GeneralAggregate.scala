package no.nextgentel.oss.akkatools.aggregate

import akka.actor._
import akka.persistence.AtLeastOnceDelivery.UnconfirmedWarning
import no.nextgentel.oss.akkatools.persistence._
import java.util.{List => JList}
import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConversions._
import scala.reflect._

class AggregateError(errorMsg:String) extends RuntimeException(errorMsg)


trait AggregateState[E, T <: AggregateState[E,T]] {
  def transition(event:E):T

}

/**
 * Dispatcher - When sending something to an ES, use its dispatcher
 * Command - Dispatchable message - When sent to the dispatcher, it will be sent to the correct ES.
 *
 * Event - Represents a change of state for an ES
 *
 * State is immutable.
 *   Represents the full state of the entity. based on its state it can accept or reject an event.
 *   Has with method transition(event) - if ok, it returns new state. If not, an error is thrown.
 *
 *   Can be used to try an event (since it is mutable)
 *
 * DurableMessage: method of sending a message which with retry-mechanism until confirm() is called.
 *
 * GeneralAggregate pseudocode:
 *
 * for each received cmd:
 *    convert it to event
 *    try the event (by calling state.transition() )
 *      if it failed: maybe do something
 *      if it works:
 *        persist event
 *        generate and send DurableMessages
 *        change our current state (by calling state.transition() and keeping the result )
 *
 *
 */
abstract class GeneralAggregate[E:ClassTag, S <: AggregateState[E, S]:ClassTag]
(
  myDispatcherActor:ActorPath
  ) extends EnhancedPersistentShardingActor[E, AggregateError](myDispatcherActor) {

  var state:S

  // This one is only valid when we're in the process of applying an event inside generateResultingDurableMessages
  private var _nextState:Option[S] = None

  def nextState():S = _nextState.getOrElse(throw new Exception("nextState can only be used from inside generateResultingDurableMessages"))

  private val defaultSuccessHandler = () => log.debug("No cmdSuccess-handler executed")
  private val defaultErrorHandler = (errorMsg:String) => log.debug("No cmdFailed-handler executed")


  private val defaultResultingDurableMessages = (e:E) => {
    log.debug("No durableMessages generated for this event")
    ResultingDurableMessages(List())
  }

  def cmdToEvent:PartialFunction[AggregateCmd, ResultingEvent[E]]
  def generateResultingDurableMessages:PartialFunction[E, ResultingDurableMessages] = Map.empty


  final override protected def stateInfo(): String = state.toString

  final def tryCommand = {
    case x:AggregateCmd =>
      // Can't get pattern-matching to work with generics..
      if (x.isInstanceOf[GetState]) {
        sender ! state
      } else {
        val cmd = x
        val defaultCmdToEvent:(AggregateCmd) => ResultingEvent[E] = {(q) => throw new AggregateError("Do not know how to process cmd of type " + q.getClass)}
        val eventResult:ResultingEvent[E] = cmdToEvent.applyOrElse(cmd, defaultCmdToEvent)
        // Test the events
        try {
          if( log.isDebugEnabled ) log.debug("Trying resultingEvents: " + eventResult.events)
          eventResult.events.foldLeft(state) {
            (s, e) =>
              s.transition(e)
          }

          // it was valid

          Option(eventResult.afterValidationSuccessHandler).map(_.apply())

          val runTheSuccessHandler = () => Option(eventResult.successHandler).getOrElse(defaultSuccessHandler).apply()


          if (eventResult.events.isEmpty) {
            // We have no events to persist - run the successHandler any way
            runTheSuccessHandler.apply()
          } else {
            // we can persist it
            persistAndApplyEvents(eventResult.events,
              successHandler = {
                () =>
                  // run the successHandler
                  runTheSuccessHandler.apply()
              })
          }
        } catch {
          case error:AggregateError =>
            Option(eventResult.errorHandler).getOrElse(defaultErrorHandler).apply(error.getMessage)
            throw error
        }
      }
    case x:AnyRef => throw new AggregateError("Do not know how to process cmd of type " + x.getClass)
  }


  def onEvent = {
    case e:E =>

      // Store nextState - the state we're in the processes of applying into - so that it is available
      // through nextState() from inside generateResultingDurableMessages
      _nextState = Some(state.transition(e))
      val resultingDurableMessages = generateResultingDurableMessages.applyOrElse(e, defaultResultingDurableMessages)
      state = _nextState.get // pop the nextState and make it current
      _nextState = None // clear next state

      // From java resultingDurableMessages might be null.. Wrap it optional
      Option(resultingDurableMessages).map {
        rdm =>
          rdm.list.foreach {
            msg =>
              if(log.isDebugEnabled) log.debug(s"Sending generated DurableMessage: $msg")
              sendAsDurableMessage(msg)
          }
      }
  }


  private var tmpStateWhileProcessingUnconfirmedWarning:S = null.asInstanceOf[S]

  // We need to override this so that we can use a fresh copy of the state while we process
  // all the unconfirmed messages
  override protected def internalProcessUnconfirmedWarning(unconfirmedWarning: UnconfirmedWarning): Unit = {
    tmpStateWhileProcessingUnconfirmedWarning = state // since we're maybe goint to validate multiple events in a row,
    // we need to have a copy of the state that we can modify during the processing/validation
    super.internalProcessUnconfirmedWarning(unconfirmedWarning)
    tmpStateWhileProcessingUnconfirmedWarning = null.asInstanceOf[S]
  }

  /**
   * If doUnconfirmedWarningProcessing is turned on, then override this method
   * to try to do something useful before we give up
   * @param originalPayload
   */
  override protected def durableMessageNotDeliveredHandler(originalPayload: Any, errorMsg: String): Unit = {
    // call generateEventsForFailedDurableMessage to let the app decide if this should result in any events that should be persisted.

    val events = generateEventsForFailedDurableMessage(originalPayload, errorMsg)
    var tmpState = tmpStateWhileProcessingUnconfirmedWarning // we must validate that the events are valid

    events.foreach {
      e => tmpState = tmpState.transition(e)
    }

    // all events passed validation => we can persist them
    persistAndApplyEvents(events.toList)

    tmpStateWhileProcessingUnconfirmedWarning = tmpState
  }

  /**
   * Override this to decide if the failed outbound durableMessage should result in a persisted event.
   * If so, return these events.
   * When these have been persisted, generateResultingDurableMessages() will be called as usual enabling
   * you to perform some outbound action.
   * @param originalPayload
   * @param errorMsg
   * @return
   */
  def generateEventsForFailedDurableMessage(originalPayload: Any, errorMsg: String):Seq[E] = Seq() // default implementation
}

object ResultingEvent {
  def apply[E](event:E):ResultingEvent[E] = new ResultingEvent[E](List(event), null, null, null)
  def apply[E](events:List[E]):ResultingEvent[E] = new ResultingEvent[E](events, null, null, null)
  def empty[E]():ResultingEvent[E] = new ResultingEvent[E](List(), null, null, null)
}

case class ResultingEvent[+E](
                               events:List[E],
                               errorHandler:(String)=>Unit,
                               successHandler:()=>Unit,
                               afterValidationSuccessHandler: () => Unit) {


  @deprecated("Use onError instead", "1.0.3")
  def withErrorHandler(errorHandler:(String)=>Unit) = onError(errorHandler)

  @deprecated("Use onSuccess instead. IMPORTANT!! Note that the two methods are used differently: Before: '() => {your code}'  Now: '{your code}'", "1.0.3")
  def withSuccessHandler(successHandler: ()=>Unit) = copy( successHandler = successHandler)


  // Newer more fluent api

  // Called whenever an AggregateError happens - either in state validation or withPostValidationHandler
  def onError(errorHandler:(String)=>Unit) = copy( errorHandler = errorHandler)

  // Called after a valid event is persisted
  def onSuccess(handler: => Unit) = copy( successHandler = () => handler)

  // Called after event is validated as success but before it is persisted
  def onAfterValidationSuccess(handler: => Unit) = copy(afterValidationSuccessHandler = () => handler)
}

object ResultingDurableMessages {
  def apply(message:AnyRef, destination:ActorPath):ResultingDurableMessages = ResultingDurableMessages(List(SendAsDurableMessage(message, destination)))
  def apply(sendAsDurableMessage: SendAsDurableMessage):ResultingDurableMessages = ResultingDurableMessages(List(sendAsDurableMessage))
}

case class ResultingDurableMessages(list:List[SendAsDurableMessage])



class GeneralAggregateView[E:ClassTag, S <: AggregateState[E, S]:ClassTag]
(
  persistenceIdBase:String,
  id:String,
  initialState:S,
  collectHistory:Boolean = true
  ) extends EnhancedPersistentView[E, S](persistenceIdBase, id, collectHistory) {

  var state:S = initialState

  override def currentState():S = state

  override def applyEventToState(event: E): Unit = {
    state = state.transition(event)
  }

  override val onCmd: PartialFunction[AnyRef, Unit] = {
    case x:GetState =>
      sender ! state
  }
}