package no.nextgentel.oss.akkatools.example.booking

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.Status.Failure
import akka.actor.{ActorPath, ActorSystem, Props}
import no.nextgentel.oss.akkatools.aggregate._

import scala.concurrent.duration.FiniteDuration


// Aggregate
class BookingAggregate(ourDispatcherActor: ActorPath, ticketPrintShop: ActorPath, cinemaNotifier: ActorPath)
  extends GeneralAggregate[BookingEvent, BookingState](FiniteDuration(60, TimeUnit.SECONDS), ourDispatcherActor) {

  var state = BookingState.empty() // This is our initial state(Machine)

  // transform command to event
  override def cmdToEvent = {
    case c: OpenBookingCmd  =>  ResultingEvent(BookingOpenEvent(c.seats))

    case c: CloseBookingCmd => ResultingEvent(BookingClosedEvent())

    case c: ReserveSeatCmd  =>
      // Generate a random seatId
      val seatId = UUID.randomUUID().toString
      val event = ReservationEvent(seatId)

      ResultingEvent(event)
        .withSuccessHandler(      () => sender ! seatId ) // Send the seatId back
        .withErrorHandler ( errorMsg => sender ! Failure(new Exception(errorMsg)) )

    case c: CancelSeatCmd =>
      ResultingEvent(CancelationEvent(c.seatId))
        .withSuccessHandler(       () => sender ! "ok")
        .withErrorHandler( (errorMsg) => sender ! Failure(new Exception(errorMsg)) )
  }

  override def generateResultingDurableMessages = {
    case e: BookingClosedEvent =>
      // The booking has now been closed and we need to send an important notification to the Cinema
      val cinemaNotification = "Booking has closed with " + state.reservations.size + " seats reserved"
      ResultingDurableMessages(cinemaNotification, cinemaNotifier)

    case e: ReservationEvent =>
      // The seat-reservation has been confirmed and we need to print the ticket

      val printShopMessage = "Ticket " + e.id + " is a valid ticket!"
      ResultingDurableMessages(printShopMessage, ticketPrintShop)
  }
}

object BookingAggregate {
  def props(ourDispatcherActor: ActorPath, ticketPrintShop: ActorPath, cinemaNotifier: ActorPath) =
    Props(new BookingAggregate(ourDispatcherActor, ticketPrintShop, cinemaNotifier))
}


// Setting up the builder we're going to use for our BookingAggregate and view
class BookingAggregateBuilder(actorSystem: ActorSystem) extends GeneralAggregateBuilder[BookingEvent, BookingState](actorSystem, "booking", Some(BookingState.empty())) {

  def config(ticketPrintShop: ActorPath, cinemaNotifier: ActorPath): Unit = {
    withGeneralAggregateProps {
      ourDispatcher: ActorPath =>
        BookingAggregate.props(ourDispatcher, ticketPrintShop, cinemaNotifier)
    }
  }

}