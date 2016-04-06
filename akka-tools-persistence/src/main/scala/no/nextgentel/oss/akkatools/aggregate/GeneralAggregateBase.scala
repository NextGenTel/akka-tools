package no.nextgentel.oss.akkatools.aggregate

import akka.actor.ActorPath
import akka.persistence.AtLeastOnceDelivery.UnconfirmedWarning
import no.nextgentel.oss.akkatools.persistence.{EnhancedPersistentShardingActor, GetState}

import scala.reflect.ClassTag

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
abstract class GeneralAggregateBase[E:ClassTag, S <: AggregateState[E, S]:ClassTag]
(
  dmSelf:ActorPath
  ) extends EnhancedPersistentShardingActor[E, AggregateError](dmSelf) {

  var state:S

  private val defaultSuccessHandler = () => log.debug("No cmdSuccess-handler executed")
  private val defaultErrorHandler = (errorMsg:String) => log.debug("No cmdFailed-handler executed")


  def cmdToEvent:PartialFunction[AggregateCmd, ResultingEvent[E]]

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

  // Called AFTER event has been applied to state
  def generateDM(event:E, previousState:S):ResultingDurableMessages

  def onEvent = {
    case e:E =>

      val resultingDMs: ResultingDurableMessages = {

        val stateBackup = state
        try {
          val previousState:S = state
          state = state.transition(e) // make the new state current
          generateDM(e, previousState)
        } catch {
          case e:Exception =>
            state = stateBackup // Must revert changes to state
            throw e // rethrow it

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
