package no.nextgentel.oss.akkatools.example.booking

import no.nextgentel.oss.akkatools.aggregate.{AggregateState, AggregateError}

// Events

trait BookingEvent

case class BookingOpenEvent(numberOfFreeSeats: Int) extends BookingEvent

case class ReservationEvent(id: String) extends BookingEvent

case class CancelationEvent(id: String) extends BookingEvent

case class BookingClosedEvent() extends BookingEvent


case class BookingError(e: String) extends AggregateError(e)

// State (machine)

object StateName extends Enumeration {
  type StateName = Value
  val NOT_OPEN = Value("NOT_OPEN")
  val OPEN = Value("OPEN")
  val CLOSED = Value("CLOSED")
}

import StateName._

object BookingState {
  def empty() = BookingState(NOT_OPEN, 0, Set())
}

case class BookingState
(
  state: StateName,
  seats: Int,
  reservations: Set[String]
  ) extends AggregateState[BookingEvent, BookingState] {

  override def transition(event: BookingEvent): BookingState = {
    if (NOT_OPEN == state) {
      transitionWhenBookingHasNotOpenedYet(event)
    } else {
      transitionWhenBookingHasOpened(event)
    }
  }

  def transitionWhenBookingHasNotOpenedYet(event: BookingEvent): BookingState = {
    // The only valid event now is to open the booking
    event match {
      case e: BookingOpenEvent =>
        // we're opening the booking
        BookingState(OPEN, e.numberOfFreeSeats, Set())

      case e: BookingEvent => throw BookingError("Invalid event since Booking is not opened yet")
    }
  }

  def transitionWhenBookingHasOpened(event:BookingEvent): BookingState = {
    // Booking has been opened, but is it closed?
    if (CLOSED == state) throw BookingError("Booking is close")

    event match {
      case e: ReservationEvent =>
        // We must try to book
        if (reservations.size >= seats)
          throw BookingError("No more seats available")
        else // Add id to reservation-list
          copy(reservations = reservations + e.id)

      case e: CancelationEvent =>
        // Must verify that this is a valid booking to cancel
        if (reservations.contains(e.id)) {
          // Remove the booing
          copy(reservations = reservations - e.id)
        } else
          throw BookingError("Not a valid booking")

      case e: BookingClosedEvent =>
        // Closing the booking
        copy(state = CLOSED)

      case e: BookingEvent =>
        throw BookingError("Not a valid event for this open booking")
    }
  }
}
