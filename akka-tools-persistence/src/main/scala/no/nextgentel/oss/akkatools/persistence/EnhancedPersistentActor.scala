package no.nextgentel.oss.akkatools.persistence

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.event.Logging.MDC
import akka.persistence.AtLeastOnceDelivery.UnconfirmedDelivery
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, PersistentView, RecoveryCompleted}
import com.fasterxml.jackson.annotation.JsonTypeInfo
import no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializable

import scala.concurrent.duration.FiniteDuration
import scala.reflect._

case class SendAsDM(payload: AnyRef, destinationActor: ActorPath, confirmationRoutingInfo: AnyRef = null)

@deprecated(message = "Use SendAsDM and GeneralAggregateBase instead", since = "1.0.7")
case class SendAsDurableMessage(payload: AnyRef, destinationActor: ActorPath, confirmationRoutingInfo: AnyRef = null) {
  def toSendAsDM() = SendAsDM(payload, destinationActor, confirmationRoutingInfo)
}

object EnhancedPersistentActor {
  // Before we calculated the timeout based on redeliverInterval and warnAfterNumberOfUnconfirmedAttempts,
  // This timeout used to be 240 seconds.
  // 215 seconds results in the same default timeout when using default config since
  // 240 - ( redeliverInterval(default 5 secs) * warnAfterNumberOfUnconfirmedAttempts(default 5) ) == 215
  val DEFAULT_TIME_TO_WAIT_AFTER_MAX_REDELIVER_ATTEMPTS_BEFORE_TIMEOUT = FiniteDuration(215, TimeUnit.SECONDS)
}

/**
  *
  * @tparam E     Superclass/trait representing your events
  * @tparam Ex    Exception-type representing a "known error" - not a failure
  */
abstract class EnhancedPersistentActor[E:ClassTag, Ex <: Exception : ClassTag]
  extends Actor
  with PersistentActor
  with AtLeastOnceDelivery
  with DiagnosticActorLogging
  with BeforeAndAfterEventAndCommand[E]
  with MdcSupport[E] {

  implicit val ec = context.dispatcher

  private   var isProcessingEvent = false
  private   var pendingDurableMessage:Option[DurableMessage] = None
  private   var timeoutTimer:Option[Cancellable] = None

  // Used when processing events live - not recovering
  private   var eventLogLevelInfo = true

  // Used when recovering events
  private   var recoveringEventLogLevelInfo = false

  // Used when processing commands
  private   var cmdLogLevelInfo = false

  // Will be set using the correct logLevel when starting to do something
  protected var currentLogLevelInfo = eventLogLevelInfo

  private var prevLogLevelTryCommand: Boolean = currentLogLevelInfo
  private var persistAndApplyEventHasBeenCalled = false

  // Used to turn on or of processing of UnconfirmedWarnings
  protected def doUnconfirmedWarningProcessing() = true

  protected lazy val idleTimeoutValueToUse = {
    val timeout = idleTimeout()
    log.debug(s"Using idleTimeout=${timeout.toSeconds}s")
    timeout
  }

  // Info about all already processed (successfully) inbound DMs.
  // If we see one of these again, we know that it is a resending caused by the sender not
  // getting the DM-confirm.
  // So instead of trying to process them - and fail since we've already processed them,
  // we can 'ignore' them...
  // But we cannot just ignore them:
  // If the sender never got our DMReceived, we must send it again..
  // And since the impl might not have just sent confirm, but used the DM with a new payload to send
  // a cmd back to the sender via the DM-confirm, we must facilitate for that to also work again..
  // You know.... idempotent cmds :)
  private var processedDMs = Set[ProcessedDMEvent]() // DMs without payload

  /**
   * @param eventLogLevelInfo Used when processing events live - not recovering
   * @param recoveringEventLogLevelInfo Used when recovering events
   * @param cmdLogLevelInfo Used when processing commands
   */
  protected def setLogLevels(eventLogLevelInfo: Boolean, recoveringEventLogLevelInfo: Boolean, cmdLogLevelInfo: Boolean) {
    this.eventLogLevelInfo = eventLogLevelInfo
    this.recoveringEventLogLevelInfo = recoveringEventLogLevelInfo
    this.cmdLogLevelInfo = cmdLogLevelInfo
  }


  private def cancelTimeoutTimer(): Unit = {
    timeoutTimer.map { t => t.cancel() }
    timeoutTimer = None
  }

  protected def defaultTimeToWaitAfterMaxRedeliverAttemptsBeforeTimeout():FiniteDuration = EnhancedPersistentActor.DEFAULT_TIME_TO_WAIT_AFTER_MAX_REDELIVER_ATTEMPTS_BEFORE_TIMEOUT

  // Override this one to set different timeout
  def idleTimeout():FiniteDuration = {
    val durationUntilAllRetryAttemptsHasBeenTried = redeliverInterval.mul(warnAfterNumberOfUnconfirmedAttempts)
    durationUntilAllRetryAttemptsHasBeenTried + defaultTimeToWaitAfterMaxRedeliverAttemptsBeforeTimeout()
  }

  private def startTimeoutTimer(): Unit = {
    cancelTimeoutTimer()
    timeoutTimer = Some(context.system.scheduler.scheduleOnce(idleTimeoutValueToUse, self, PersistentActorTimeout()))
  }

  override def preStart(): Unit = {
    super.preStart()
    startTimeoutTimer()
  }

  override def postStop {
    super.postStop
    log.debug("Stopped")
    cancelTimeoutTimer()
  }

  private def processingRecoveringMessageStarted {
    log.mdc( log.mdc + ("akkaPersistenceRecovering" -> "[recovering]") )
  }

  private def processingRecoveringMessageEnded {
    log.mdc( log.mdc - "akkaPersistenceRecovering" )
  }

  override def receiveRecover: Receive = {
    case r: DurableMessageReceived =>
      processingRecoveringMessageStarted
      try {
        onDurableMessageReceived(r)
      } finally {
        processingRecoveringMessageEnded
      }
    case e:ProcessedDMEvent =>
      onProcessedDMEvent(e)
    case c:RecoveryCompleted =>
      log.debug("Recover complete")
    case event:AnyRef =>
      onReceiveRecover(event.asInstanceOf[E])
  }

  protected def onReceiveRecover(event:E) {
    val prevLogLevel = currentLogLevelInfo
    currentLogLevelInfo = recoveringEventLogLevelInfo
    processingRecoveringMessageStarted
    try {
      onEventInternal(event)
    } finally {
      currentLogLevelInfo = prevLogLevel
      processingRecoveringMessageEnded
    }
  }

  def logMessage(message:String): Unit ={
    if (currentLogLevelInfo) {
      log.info(message)
    } else {
      log.debug(message)
    }
  }

  protected def onApplyingLiveEvent(event: E) {
    val prevLogLevel = currentLogLevelInfo
    currentLogLevelInfo = eventLogLevelInfo
    try {
      onEventInternal(event)
    } finally {
      currentLogLevelInfo = prevLogLevel
    }
  }

  protected def stateInfo():String

  private def logState(): Unit = {
    logMessage("State: " + stateInfo())
  }

  protected def toStringForLogging[T](o: T): String = {
    Option(o).map(_.getClass.getSimpleName).getOrElse("null")
  }

//  protected def expectedExceptionType[T <: Exception]():Class[T]
//
//  private def isExpectedException(exception: Exception): Boolean = {
//    expectedExceptionType.isAssignableFrom(exception.getClass)
//  }

  protected def isExpectedError(e:Exception):Boolean = {
    classTag[Ex].runtimeClass.isInstance(e)
  }

  private def onEventInternal(event:E) {

    isProcessingEvent = true
    beforeOnEvent(event)
    logMessage("Applying: " + toStringForLogging(event))
    try {
      onEvent.apply(event)
      logState
    }
    catch {
      case e:Exception =>
        if (isExpectedError(e)) {
          log.warning("Error applying: '{}' Event: {}", e, toStringForLogging(event))
        } else {
          log.error(e, "Error applying event: {}", toStringForLogging(event))
        }
    } finally {
      afterOnEvent()
      isProcessingEvent = false
    }
  }

  def onEvent:PartialFunction[E,Unit]

  private def onDurableMessageReceived(msg: DurableMessageReceived) {
    log.debug("Remembering DurableMessageReceived with DeliveryId={}", msg.deliveryId)
    // We also need to confirm it here: If live, this is the second time, but if recovering it is the first real time.
    confirmDelivery(msg.deliveryId)
  }

  protected def persistAndApplyEvent(event:E):Unit = persistAndApplyEvent(event, {() => Unit})

  protected def persistAndApplyEvent(event:E, successHandler: () => Unit):Unit = persistAndApplyEvents( List(event), successHandler)

  protected def persistAndApplyEvents(events: List[E]):Unit = persistAndApplyEvents(events, { () => Unit})

  // All events in events are persisted and onApplyingLiveEvent() is executed.
  // When all events in list are successfully processed, we exeute the successHandler
  protected def persistAndApplyEvents(events: List[E], successHandler: () => Unit):Unit = {
    if (!events.isEmpty) {
      // We need to have a counter so that we can call successHandler when we have
      // executed the last successfull persistAll-handler

      persistAndApplyEventHasBeenCalled = true // This will supress the dm-cleanup until after successHandler has been executed

      // If inbound cmd came via DM, we must also persist that we've processed This DM
      val _events:List[Any] = pendingDurableMessage match {
        case Some(dm) => ProcessedDMEvent.createFromDM(dm) :: events
        case None     => events
      }


      var callbacksLeft = _events.size
      persistAll(_events) {
        e =>
          callbacksLeft = callbacksLeft - 1
          e match {
            case e:ProcessedDMEvent => onProcessedDMEvent(e)
            case e:Any              => onApplyingLiveEvent(e.asInstanceOf[E])
          }

          if (callbacksLeft == 0) {
            // This was the last time - we should call the successHandler
            successHandler.apply()

            // Now we can do the last DM cleanup
            doTryCommandCleanupAndConfirmDMIfSuccess(true)
          }
      }
    }
  }

  private def onProcessedDMEvent(processedDMEvent: ProcessedDMEvent): Unit = {
    processedDMs = processedDMs + processedDMEvent
  }

  /**
   * Called when actor has been idle for too long..
   *
   * If running in sharding, you should stop like this:
   *
   * getContext().parent().tell(new ShardRegion.Passivate(PoisonPill.getInstance()), self());
   */
  protected def onInactiveTimeout():Unit


  override def receiveCommand: Receive = {
    case timeout:PersistentActorTimeout =>
      log.debug("Stopping due to inactivity")
      onInactiveTimeout()
    case r:DurableMessageReceived =>
      cancelTimeoutTimer()

      // We must try to confirmDelivery to see if this is the first time we get this confirmation.
      // We should only persist it if it is the first time
      if (confirmDelivery(r.deliveryId)) {
        persist(r) {
          e => onDurableMessageReceived(e)
        }
      }

      startTimeoutTimer()
    case cmd:AnyRef =>
      tryCommandInternal(cmd)
  }

  def tryCommand:PartialFunction[AnyRef,Unit]

  private def tryCommandInternal(rawCommand: AnyRef) {

    // We will set persistAndApplyEventHasBeenCalled=true IF persistAndApplyEvent
    // has been called during tryCommand-processing...
    // If it has, we have to wait until its successHandlers have been called
    // before we know if we should confirm incomming DM or not.... It might be "used" (withNewPayload)
    // in that successhandler, and then we should not confirm it.
    persistAndApplyEventHasBeenCalled = false

    prevLogLevelTryCommand = currentLogLevelInfo
    currentLogLevelInfo = cmdLogLevelInfo
    cancelTimeoutTimer
    pendingDurableMessage = None
    val command: AnyRef = rawCommand match {
      case dm:DurableMessage =>
        pendingDurableMessage = Some(dm)
        dm.payload
      case x:AnyRef => x
    }

    beforeTryCommand(command)
    logMessage("Processing: " + toStringForLogging(command))

    try {
      if (doUnconfirmedWarningProcessing && (command.isInstanceOf[AtLeastOnceDelivery.UnconfirmedWarning])) {
        internalProcessUnconfirmedWarning(command.asInstanceOf[AtLeastOnceDelivery.UnconfirmedWarning])
      } else if (pendingDurableMessage.isDefined && processedDMs.contains( ProcessedDMEvent.createFromDM(pendingDurableMessage.get) )) {
        onAlreadyProcessedCmdViaDMReceivedAgain(command)
      } else {
        tryCommand.apply(command)
      }

      if (!persistAndApplyEventHasBeenCalled) {
        // Since processing of this cmd resulting in no events being persisted,
        // we should not wait to cleanup DM
        doTryCommandCleanupAndConfirmDMIfSuccess(true)
      }

    }
    catch {
      case e:Exception =>
        if (isExpectedError(e)) {
          log.warning("Error processing:  '{}' : {}", toStringForLogging(command), e.getMessage)
          doTryCommandCleanupAndConfirmDMIfSuccess(true)
        } else {
          log.error(e, "Error processing: " + toStringForLogging(command))
          doTryCommandCleanupAndConfirmDMIfSuccess(false)
        }
    }
    startTimeoutTimer
  }

  protected def onAlreadyProcessedCmdViaDMReceivedAgain(cmd:AnyRef): Unit ={
    log.warning(s"Received already processed DM again: $cmd")
  }

  private def doTryCommandCleanupAndConfirmDMIfSuccess(confirm:Boolean): Unit = {

    if ( confirm) {
      pendingDurableMessage match {
        case Some(dm) =>
          dm.confirm(context, self)
          log.debug("Inbound-DM-cleanup: DM confirmed")
        case None     =>
          log.debug("Inbound-DM-cleanup: No inbound DM")
      }
    } else {
      log.debug("Inbound-DM-cleanup: Not confirming")
    }
    pendingDurableMessage = None
    currentLogLevelInfo = prevLogLevelTryCommand
    afterTryCommand()
  }

  /**
   * If doUnconfirmedWarningProcessing is turned on, then override this method
   * to try to do something useful before we give up
 *
   * @param originalPayload
   */
  protected def durableMessageNotDeliveredHandler(originalPayload:Any, errorMsg: String) {
  }

  protected def internalProcessUnconfirmedWarning(unconfirmedWarning: AtLeastOnceDelivery.UnconfirmedWarning) {

    unconfirmedWarning.unconfirmedDeliveries.map {
      ud: UnconfirmedDelivery =>
        val errorMsg: String = "Not getting message-confirmation from: " + ud.destination + " - giving up"
        log.error(s"$errorMsg: $ud")

        // invoke the handler for the payload
        try {
          val originalPayload = ud.message match {
            case dm:DurableMessage => dm.payload // extract the original payload
            case x:Any             => x // Not a dm (??) - Use it as it is
          }
          durableMessageNotDeliveredHandler(originalPayload, errorMsg)
        } catch {
          case e: Exception => {
            if (isExpectedError(e)) {
              log.warning("durableMessageNotDeliveredHandler() failed while trying to give up: {}", e.getMessage)
            } else {
              log.error(e, "durableMessageNotDeliveredHandler() failed while trying to give up")
            }
          }
        }

        // Cannot call confirmDelivery directly because we need to remember that we have
        // decided to threat this failed delivery as "delivered"
        val persistableDurableMessageReceived = DurableMessageReceived(ud.deliveryId, None)
        persist(persistableDurableMessageReceived) {
          e => onDurableMessageReceived(e)
        }

    }
  }

  // This is used as 'self' when sending DMs.. When receiver is confirming DM, the confirmation is sent to this actor.
  protected def getDMSelf(): ActorPath = {
    return self.path
  }

  protected def sendAsDM(payload: AnyRef, destinationActor: ActorPath) {
    sendAsDM( SendAsDM(payload, destinationActor) )
  }

  protected def sendAsDM(payload: AnyRef, destinationActor: ActorPath, confirmationRoutingInfo: AnyRef): Unit = {
    sendAsDM( SendAsDM(payload, destinationActor, confirmationRoutingInfo) )
  }

  protected def sendAsDM(sendAsDm: SendAsDM) {
    if (isProcessingEvent) {
      deliver(sendAsDm.destinationActor) {
        deliveryId:Long =>
          DurableMessage(deliveryId, sendAsDm.payload, getDMSelf(), sendAsDm.confirmationRoutingInfo)
      }
    }
    else {
      val outgoingDurableMessage = pendingDurableMessage.getOrElse( {throw new RuntimeException("Cannot send durableMessage while not processingEvent nor having a pendingDurableMessage")}).withNewPayload(sendAsDm.payload)
      context.actorSelection(sendAsDm.destinationActor).tell(outgoingDurableMessage, self)
      pendingDurableMessage = None
    }
  }

  protected def canSendAsDM():Boolean = isProcessingEvent || pendingDurableMessage.isDefined


}

trait BeforeAndAfterEventAndCommand[E] extends DiagnosticActorLogging {

  // nice place to do mdc stuff
  protected def beforeOnEvent(event:E):Unit = {}
  protected def afterOnEvent():Unit= {}

  protected def beforeTryCommand(cmd:AnyRef):Unit = {}
  protected def afterTryCommand():Unit = {}

}

trait MdcSupport[E] extends BeforeAndAfterEventAndCommand[E] {

  // override to set mdc data which should always be set
  protected def defaultMdc():Unit = {}

  override def mdc(currentMessage: Any): MDC = {
    defaultMdc()
    log.mdc
  }

  protected def setMdcValue(name:String, value:String): Unit ={
    log.mdc( log.mdc + (name -> value) )
  }

  // override this method to extract mdc stuff from event or cmd
  protected def extractMdc(eventOrCmd:AnyRef): Unit = {}

  // nice place to do mdc stuff
  override protected def beforeOnEvent(event: E): Unit = {
    super.beforeOnEvent(event)
    extractMdc(event.asInstanceOf[AnyRef])
  }

  override protected def beforeTryCommand(cmd: AnyRef): Unit = {
    super.beforeTryCommand(cmd)
    extractMdc(cmd.asInstanceOf[AnyRef])
  }

}

object ProcessedDMEvent {
  def createFromDM(dm:DurableMessage) = ProcessedDMEvent(dm.deliveryId, dm.sender, dm.confirmationRoutingInfo)
}

case class ProcessedDMEvent(
                             deliveryId:Long,
                             sender:String,
                             @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@confirmationRoutingInfo_class")
                             confirmationRoutingInfo:AnyRef
                           ) extends JacksonJsonSerializable


case class PersistentActorTimeout private [persistence] ()


abstract class EnhancedPersistentJavaActor[Ex <: Exception : ClassTag] extends EnhancedPersistentActor[AnyRef, Ex] with EnhancedPersistentJavaActorLike {

}

trait EnhancedPersistentJavaActorLike {

  val onEvent: PartialFunction[AnyRef, Unit] = {
    case event:AnyRef => onEventJava(event)
  }
  val tryCommand: PartialFunction[AnyRef, Unit] = {
    case cmd:AnyRef => tryCommandJava(cmd)
  }

  def onEventJava(event:AnyRef):Unit
  def tryCommandJava(cmd:AnyRef):Unit
}


// The only purpose of this class, EventAndState, is to transport the data to json..
// Therefor generic types etc is just in the way..
case class EventAndState(eventType:String, event:AnyRef, state:AnyRef)

case class GetEventAndStateHistory()

abstract class EnhancedPersistentView[E:ClassTag, S:ClassTag](persistenceIdBase:String, id:String, collectHistory:Boolean = true) extends PersistentView with ActorLogging {

  log.debug(s"Starting view with persistenceIdBase=$persistenceIdBase and id=$id")

  var history:List[EventAndState] = List()

  override def viewId = persistenceIdBase + "-view-" + id

  def currentState():S

  def applyEventToState(event:E)

  private def internalApplyEventToState(event:E): Unit = {
    try {
      applyEventToState(event)
    } catch {
      case e:Exception =>
        handleEventException(e, event)
    }
  }

  def handleEventException(e:Exception, event:E):Unit = {
    log.error(e, s"Error applying event, ignoring it: $event" )
  }


  override def persistenceId: String = persistenceIdBase + id

  val onCmd:PartialFunction[AnyRef, Unit]

  override def receive = {
    case GetEventAndStateHistory() =>
      log.debug("Sending EventAndStateHistory")
      sender ! history
    case x:DurableMessageReceived => // We can ignore these in our view
    case x:GetState =>
      log.debug("Sending state")
      sender ! currentState()
    case x:AnyRef =>
      if (classTag[E].runtimeClass.isInstance(x) ) {
        val event = x.asInstanceOf[E]
        log.debug(s"Applying event to state: $event")
        internalApplyEventToState(event)
        history = history :+ EventAndState(event.getClass.getName, event.asInstanceOf[AnyRef], currentState().asInstanceOf[AnyRef])
      } else {
        onCmd.applyOrElse(x, {
          (cmd:AnyRef) =>
            log.debug(s"No cmdHandler found for $cmd")
        })
      }

  }
}