package no.nextgentel.oss.akkatools.aggregate

import akka.actor._
import akka.persistence.AtLeastOnceDelivery.UnconfirmedWarning
import no.nextgentel.oss.akkatools.persistence._
import java.util.{List => JList}
import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConversions._
import scala.reflect._









@deprecated(message = "This impl is too complicated with all the different generateDM*-methods - Instead use GeneralAggregateBAse which has one single generic generateDM-method", since = "1.0.7")
abstract class GeneralAggregate[E:ClassTag, S <: AggregateState[E, S]:ClassTag]
(
  dmSelf:ActorPath
) extends GeneralAggregateBase[E, S](dmSelf) {

  // This one is only valid when we're in the process of applying an event inside generateResultingDurableMessages
  private var _nextState:Option[S] = None

  def nextState():S = _nextState.getOrElse(throw new Exception("nextState can only be used from inside generateDMBefore"))

  // This one is only valid when we're in the process of applying an event inside generateDM
  private var _previousState:Option[S] = None
  def previousState():S = _previousState.getOrElse(throw new Exception("previousState can only be used from inside generateDMAfter"))

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

  def generateDM(e:E, previousState:S):ResultingDurableMessages = {
    try {
      generateDMInfo match {
        case GenerateDMInfo(Some(generate), None, None, true) =>
          _previousState = Some(previousState) // keep the old state available as long as we execute generateDM
          generate.applyOrElse(e, defaultGenerateDMViaEvent)

        case GenerateDMInfo(None, Some(generate), None, true) =>
          generate.applyOrElse(state, defaultGenerateDMViaState)

        case GenerateDMInfo(None, None, Some(generate), true) =>
          generate.applyOrElse((state, e), defaultGenerateDMViaStateAndEvent)

        case GenerateDMInfo(Some(generate), None, None, false) =>
          // The event has already been applied, so to simulate this old way of
          // Doing it, we must revert the state
          val realNewState = state
          _nextState = Some(realNewState)
          state = previousState
          val resultingDurableMessages = generate.applyOrElse(e, defaultGenerateDMViaEvent)
          state = realNewState // make the new state current again
          resultingDurableMessages
        case x: GenerateDMInfo => throw new Exception("This combo is not supported..: " + x)
      }
    } finally {
      // Do some more cleanup
      _previousState = None // clear previousState state
      _nextState = None // clear next state
    }
  }
}








