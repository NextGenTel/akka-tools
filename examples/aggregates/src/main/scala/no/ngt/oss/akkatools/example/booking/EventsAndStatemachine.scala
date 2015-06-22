package no.ngt.oss.akkatools.example.booking

import no.ngt.oss.akkatools.aggregate.{AggregateState, AggregateError}

// Events

trait BookingEvent

case class BookingOpenEvent(numberOfFreeSeats: Int) extends BookingEvent

case class ReservationEvent(id: String) extends BookingEvent

case class CancelationEvent(id: String) extends BookingEvent

case class BookingClosedEvent() extends BookingEvent


case class BookingError(e: String) extends AggregateError(e)

// State (machine)

object BookingState {
  def empty() = BookingState(false, 0, Set(), false)
}

case class BookingState
(
  opened: Boolean,
  seats: Int,
  reservations: Set[String],
  closed: Boolean // finilized
  ) extends AggregateState[BookingEvent, BookingState] {

  override def transition(event: BookingEvent): BookingState = {
    if (!opened) {
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
        BookingState(true, e.numberOfFreeSeats, Set(), false)

      case e: BookingEvent => throw BookingError("Invalid event since Booking is not opened yet")
    }
  }

  def transitionWhenBookingHasOpened(event:BookingEvent): BookingState = {
    // Booking has been opened, but is it closed?
    if (closed) throw BookingError("Booking is close")

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
        copy(closed = true)

      case e: BookingEvent =>
        throw BookingError("Not a valid event for this open booking")
    }
  }
}
