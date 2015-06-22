package no.nextgentel.oss.akkatools.persistence

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.event.Logging.MDC
import akka.persistence.{PersistentView, RecoveryCompleted, AtLeastOnceDelivery, PersistentActor}

import scala.concurrent.duration.FiniteDuration
import scala.reflect._


object EnhancedPersistentActor {
  val DEFAULT_IDLE_TIMEOUT_IN_SECONDS = FiniteDuration(240, TimeUnit.SECONDS)
}

abstract class EnhancedPersistentActor[E:ClassTag, Ex <: Exception : ClassTag]
(
  idleTimeout:FiniteDuration = EnhancedPersistentActor.DEFAULT_IDLE_TIMEOUT_IN_SECONDS
  )
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

  // Used to turn on or of processing of UnconfirmedWarnings
  protected def doUnconfirmedWarningProcessing() = true

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

  private def startTimeoutTimer(): Unit = {
    cancelTimeoutTimer()
    timeoutTimer = Some(context.system.scheduler.scheduleOnce(idleTimeout, self, PersistentActorTimeout()))
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

  private def toStringForLogging[T](o: T): String = {
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
    confirmDelivery(msg.deliveryId)
  }

  protected def persistAndApplyEvent(event:E):Unit = persist(event) { e => onApplyingLiveEvent(e) }
  protected def persistAndApplyEvents(events: List[E]):Unit = persist(events) { e => onApplyingLiveEvent(e) }

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
      persist(r) {
        e => onDurableMessageReceived(e)
      }
      startTimeoutTimer()
    case cmd:AnyRef =>
      tryCommandInternal(cmd)
  }

  def tryCommand:PartialFunction[AnyRef,Unit]

  private def tryCommandInternal(rawCommand: AnyRef) {

    val prevLogLevel: Boolean = currentLogLevelInfo
    currentLogLevelInfo = cmdLogLevelInfo
    cancelTimeoutTimer
    pendingDurableMessage = None
    val command: AnyRef = rawCommand match {
      case dm:DurableMessage =>
        pendingDurableMessage = Some(dm)
        dm.payload.asInstanceOf[AnyRef]
      case x:AnyRef => x
    }

    beforeTryCommand(command)
    logMessage("Processing: " + toStringForLogging(command))

    try {
      if (doUnconfirmedWarningProcessing && (command.isInstanceOf[AtLeastOnceDelivery.UnconfirmedWarning])) {
        internalProcessUnconfirmedWarning(command.asInstanceOf[AtLeastOnceDelivery.UnconfirmedWarning])
      }
      else {
        tryCommand.apply(command)
      }

      pendingDurableMessage.map {dm => dm.confirm(context, self)}

    }
    catch {
      case e:Exception =>
        if (isExpectedError(e)) {
          log.warning("Error processing:  '{}' : {}", toStringForLogging(command), e.getMessage)
          pendingDurableMessage.map {dm => dm.confirm(context, self)}
        } else {
          log.error(e, "Error processing: " + toStringForLogging(command))
        }
    } finally {
      afterTryCommand()
      currentLogLevelInfo = prevLogLevel
      pendingDurableMessage = None
    }
    startTimeoutTimer
  }

  /**
   * If doUnconfirmedWarningProcessing is turned on, then override this method
   * to try to do someting usefull before we give up
   * @param unconfirmedWarning
   */
  protected def durableMessagesNotDeliveredHandler(unconfirmedWarning: AtLeastOnceDelivery.UnconfirmedWarning, errorMsg: String) {
  }

  private def internalProcessUnconfirmedWarning(unconfirmedWarning: AtLeastOnceDelivery.UnconfirmedWarning) {
    val destinationNotConfirming = unconfirmedWarning.unconfirmedDeliveries.find({p => true}).map(p => p.destination.toString).getOrElse("Unknown destination")
    val errorMsg: String = "Not getting message-confirmation from: " + destinationNotConfirming + " - giving up"
    log.error(s"$errorMsg: $unconfirmedWarning")
    try {
      durableMessagesNotDeliveredHandler(unconfirmedWarning, errorMsg)
    }
    catch {
      case e: Exception => {
        log.warning("durableMessagesNotDeliveredHandler() failed while trying to give up: {}", e.getMessage)
      }
    }
    unconfirmedWarning.unconfirmedDeliveries.map({
      ud =>
      // Cannot call confirmDelivery directly because we need to remember that we have
      // decided to threat this failed delivery as "delivered"
      val persistableDurableMessageReceived = DurableMessageReceived(ud.deliveryId, null)
      persist(persistableDurableMessageReceived) {
        e => onDurableMessageReceived(e)
      }
    })
  }

  protected def getDurableMessageSender(): ActorPath = {
    return self.path
  }

  protected def sendAsDurableMessage(payload: AnyRef, destinationActor: ActorPath) {
    sendAsDurableMessage(payload, destinationActor, null)
  }

  protected def sendAsDurableMessage(payload: AnyRef, destinationActor: ActorPath, confirmationRoutingInfo: AnyRef) {
    if (isProcessingEvent) {
      deliver(destinationActor, {
        deliveryId:Long =>
          DurableMessage(deliveryId, payload, getDurableMessageSender(), confirmationRoutingInfo)
      })
    }
    else {
      val outgoingDurableMessage = pendingDurableMessage.getOrElse( {throw new RuntimeException("Cannot send durableMessage while not processingEvent nor having a pendingDurableMessage")}).withNewPayload(payload)
      context.actorSelection(destinationActor).tell(outgoingDurableMessage, self)
      pendingDurableMessage = None
    }
  }


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

case class PersistentActorTimeout private [persistence] ()


abstract class EnhancedPersistentJavaActor[Ex <: Exception : ClassTag](idleTimeout:FiniteDuration) extends EnhancedPersistentActor[AnyRef, Ex](idleTimeout) with EnhancedPersistentJavaActorLike {

  def this() = this(EnhancedPersistentActor.DEFAULT_IDLE_TIMEOUT_IN_SECONDS)

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


  override def persistenceId: String = persistenceIdBase + id

  val onCmd:PartialFunction[AnyRef, Unit]

  override def receive = {
    case GetEventAndStateHistory() =>
      log.debug("Sending EventAndStateHistory")
      sender ! history
    case x:DurableMessageReceived => // We can ignore these in our view
    case x:AnyRef =>
      if (classTag[E].runtimeClass.isInstance(x) ) {
        val event = x.asInstanceOf[E]
        log.debug(s"Applying event to state: $event")
        applyEventToState(event)
        history = history :+ EventAndState(event.getClass.getName, event.asInstanceOf[AnyRef], currentState().asInstanceOf[AnyRef])
      } else {
        onCmd.applyOrElse(x, {
          (cmd:AnyRef) =>
            log.debug(s"No cmdHandler found for $cmd")
        })
      }

  }
}