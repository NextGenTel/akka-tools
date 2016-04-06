package no.nextgentel.oss.akkatools.aggregate

import scala.reflect.ClassTag

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
