package no.nextgentel.oss.akkatools.aggregate

import scala.reflect.ClassTag
import java.util.{List => JList}
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


  // Called whenever an AggregateError happens - either in state validation or withPostValidationHandler
  def onError(errorHandler:(String)=>Unit) = copy( errorHandler = errorHandler)

  // Called after a valid event is persisted
  def onSuccess(handler: => Unit) = copy( successHandler = () => handler)

  // Called after event is validated as success but before it is persisted
  def onAfterValidationSuccess(handler: => Unit) = copy(afterValidationSuccessHandler = () => handler)
}



trait ResultingEventJava_ErrorHandler {
  def onError(error: String): Unit
}

trait ResultingEventJava_SuccessHandler {
  def onSuccess(): Unit
}

trait ResultingEventJava_AfterValidationSuccessHandler {
  def onAfterValidationSuccess(): Unit
}


object ResultingEventJava {
  import scala.jdk.CollectionConverters._
  def createList(events: JList[AnyRef]): ResultingEventJava = ResultingEventJava(events, null, null, null)
  def create(event: AnyRef): ResultingEventJava = ResultingEventJava(List(event).asJava, null, null, null)
}

case class ResultingEventJava
(
  events: JList[AnyRef],
  errorHandler: ResultingEventJava_ErrorHandler,
  successHandler: ResultingEventJava_SuccessHandler,
  afterValidationSuccessHandler:ResultingEventJava_AfterValidationSuccessHandler,
) {

  def withErrorHandler(errorHandler: ResultingEventJava_ErrorHandler): ResultingEventJava = {
    this.copy(errorHandler = errorHandler)
  }

  def withSuccessHandler(successHandler: ResultingEventJava_SuccessHandler): ResultingEventJava = {
    this.copy(successHandler = successHandler)
  }

  def withAfterValidationSuccessHandler(afterValidationSuccessHandler:ResultingEventJava_AfterValidationSuccessHandler): ResultingEventJava = {
    this.copy(afterValidationSuccessHandler = afterValidationSuccessHandler)
  }
}