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
  * @param dmSelf dmSelf is used as the address where the DM-confirmation-messages should be sent.
 *               In a sharding environment, this has to be our dispatcher which knows how to reach the sharding mechanism.
 *               If null, we'll fallback to self - useful when testing
 * @tparam E     Superclass/trait representing your events
 * @tparam S     The type representing your state
 */
abstract class GeneralAggregate[E:ClassTag, S <: AggregateState[E, S]:ClassTag]
(
  dmSelf:ActorPath
  ) extends EnhancedPersistentShardingActor[E, AggregateError](dmSelf) {

  var state:S

  // This one is only valid when we're in the process of applying an event inside generateResultingDurableMessages
  private var _nextState:Option[S] = None

  def nextState():S = _nextState.getOrElse(throw new Exception("nextState can only be used from inside generateDMBefore"))

  // This one is only valid when we're in the process of applying an event inside generateDM
  private var _previousState:Option[S] = None
  def previousState():S = _previousState.getOrElse(throw new Exception("previousState can only be used from inside generateDMAfter"))

  private val defaultSuccessHandler = () => log.debug("No cmdSuccess-handler executed")
  private val defaultErrorHandler = (errorMsg:String) => log.debug("No cmdFailed-handler executed")


  private val defaultGenerateDMViaEvent = (e:E) => {
    log.debug("No durableMessages generated for this event")
    ResultingDurableMessages(List())
  }

  private val defaultGenerateDMViaState = (s:S) => {
    log.debug("No durableMessages generated for this event")
    ResultingDurableMessages(List())
  }

  private val defaultGenerateDMViaStateAndEvent = (t:(S,E)) => {
    log.debug("No durableMessages generated for this event")
    ResultingDurableMessages(List())
  }

  def cmdToEvent:PartialFunction[AggregateCmd, ResultingEvent[E]]


  // TODO: These generateXX** method really needs to be refactored into different traits...

  @deprecated("""Use generateDMBefore/generateDMAfter/generateDM instead""", since = "1.0.6")
  def generateResultingDurableMessages:PartialFunction[E, ResultingDurableMessages] = Map.empty

  // Called to generate DMs BEFORE we have applied the event.
  // nextState() returns the state you are turning into
  def generateDMBefore:PartialFunction[E, ResultingDurableMessages] = Map.empty

  // Called to generate DMs AFTER we have applied the event.
  // previousState() returns the state you are leaving
  def generateDMAfter:PartialFunction[E, ResultingDurableMessages] = Map.empty

  // Called to generate DMs AFTER we have applied the event.
  def generateDM:PartialFunction[S, ResultingDurableMessages] = Map.empty

  // Called to generate DMs AFTER we have applied the event. with both the new state and the event as input
  def generateDMSE:PartialFunction[(S,E), ResultingDurableMessages] = Map.empty


  private lazy val generateDMInfo = resolveGenerateDMInfo()

  override protected def stateInfo(): String = state.toString

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
          val eventList:List[E] = eventResult.events.apply()
          if( log.isDebugEnabled ) log.debug("Trying resultingEvents: " + eventList)
          eventList.foldLeft(state) {
            (s, e) =>
              s.transition(e)
          }

          // it was valid

          Option(eventResult.afterValidationSuccessHandler).map(_.apply())

          val runTheSuccessHandler = () => Option(eventResult.successHandler).getOrElse(defaultSuccessHandler).apply()


          if (eventList.isEmpty) {
            // We have no events to persist - run the successHandler any way
            runTheSuccessHandler.apply()
          } else {
            // we can persist it
            persistAndApplyEvents(eventList,
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


  protected case class GenerateDMInfo(generateDMViaEvent:Option[PartialFunction[E, ResultingDurableMessages]], generateDMViaState:Option[PartialFunction[S, ResultingDurableMessages]], generateDMViaStateAndEvent:Option[PartialFunction[(S,E), ResultingDurableMessages]], applyEventBefore:Boolean )

  // TODO This should be refactored into multiple traits..
  // TODO Could also add a way to get both event and state as input..
  private def resolveGenerateDMInfo():GenerateDMInfo = {

    val errorMsg = "You can only override one of the following methods at the same time: generateDM, generateDMSE, generateDMBefore and generateDMBefore( and generateResultingDurableMessages(which is Deprecated))"
    var generateDMInfo:Option[GenerateDMInfo] = None
    if ( generateResultingDurableMessages != Map.empty) {
      if ( generateDMInfo.isDefined) throw new Exception(errorMsg)
      generateDMInfo = Some(GenerateDMInfo(Some(generateResultingDurableMessages), None, None, false))
    }

    if ( generateDMBefore != Map.empty) {
      if ( generateDMInfo.isDefined) throw new Exception(errorMsg)
      generateDMInfo = Some(GenerateDMInfo(Some(generateDMBefore), None, None, false))
    }

    if ( generateDMAfter != Map.empty) {
      if ( generateDMInfo.isDefined) throw new Exception(errorMsg)
      generateDMInfo = Some(GenerateDMInfo(Some(generateDMAfter), None, None, true))
    }

    if ( generateDM != Map.empty) {
      if ( generateDMInfo.isDefined) throw new Exception(errorMsg)
      generateDMInfo = Some(GenerateDMInfo(None, Some(generateDM), None, true))
    }

    if ( generateDMSE != Map.empty) {
      if ( generateDMInfo.isDefined) throw new Exception(errorMsg)
      generateDMInfo = Some(GenerateDMInfo(None, None, Some(generateDMSE), true))
    }

    // fallback to default - which is
    generateDMInfo.getOrElse(GenerateDMInfo(None, Some(generateDM), None, true))
  }

  def onEvent = {
    case e:E =>

      val resultingDMs: ResultingDurableMessages = {

        val stateBackup = state
        try {
          generateDMInfo match {
            case GenerateDMInfo(Some(generate), None, None, true) =>
              _previousState = Some(state) // keep the old state available as long as we execute generateDM
              state = state.transition(e) // make the new state current
              generate.applyOrElse(e, defaultGenerateDMViaEvent)

            case GenerateDMInfo(None, Some(generate), None, true) =>
              state = state.transition(e) // make the new state current
              generate.applyOrElse(state, defaultGenerateDMViaState)

            case GenerateDMInfo(None, None, Some(generate), true) =>
              state = state.transition(e) // make the new state current
              generate.applyOrElse((state, e), defaultGenerateDMViaStateAndEvent)

            case GenerateDMInfo(Some(generate), None, None, false) =>
              // Store nextState - the state we're in the processes of applying into - so that it is available
              // through nextState() from inside generateResultingDurableMessages
              _nextState = Some(state.transition(e))
              val resultingDurableMessages = generate.applyOrElse(e, defaultGenerateDMViaEvent)
              state = _nextState.get // pop the nextState and make it current
              resultingDurableMessages
            case x: GenerateDMInfo => throw new Exception("This combo is not supported..: " + x)
          }
        } catch {
          case e:Exception =>
            state = stateBackup // Must revert changes to state
            throw e // rethrow it

        } finally {
          // Do some more cleanup
          _previousState = None // clear previousState state
          _nextState = None // clear next state
        }

      }

      // From java resultingDurableMessages might be null.. Wrap it optional
      Option(resultingDMs).map {
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
    *
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
    *
    * @param originalPayload
   * @param errorMsg
   * @return
   */
  def generateEventsForFailedDurableMessage(originalPayload: Any, errorMsg: String):Seq[E] = Seq() // default implementation
}

object ResultingEvent {
  def apply[E](event: => E):ResultingEvent[E] = new ResultingEvent[E](() => List(event), null, null, null)
  def apply[E:ClassTag](events: => List[E]):ResultingEvent[E] = new ResultingEvent[E](() => events, null, null, null)
  def empty[E]():ResultingEvent[E] = new ResultingEvent[E](() => List(), null, null, null)
}

case class ResultingEvent[+E](
                               events: () => List[E],
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