package no.nextgentel.oss.akkatools.example.booking

import no.nextgentel.oss.akkatools.aggregate.{AggregateError, AggregateState}

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
    (state, event) match {
      case (NOT_OPEN, e:BookingOpenEvent)   => openBooking(e.numberOfFreeSeats)
      case (NOT_OPEN, _)                    => throw BookingError("Invalid event since Booking is not opened yet")
      case (OPEN,     e:ReservationEvent)   => addReservation(e.id)
      case (OPEN,     e:CancelationEvent)   => cancelReservation(e.id)
      case (OPEN,     e:BookingClosedEvent) => closeBooking()
      case (CLOSED, _)                      => throw BookingError("Booking is closed")

    }
  }

  def openBooking(numberOfFreeSeats:Int) = BookingState(OPEN, numberOfFreeSeats, Set())

  def addReservation(id:String) = {
    // Do we have free seats?
    if (reservations.size >= seats)
      throw BookingError("No more seats available")
    else // Add id to reservation-list
      this.copy(reservations = this.reservations + id)
  }

  def cancelReservation(id:String) = {
    // Must verify that this is a valid reservation to cancel
    if (reservations.contains(id)) {
      // Remove the reservation
      this.copy(reservations = this.reservations - id)
    } else
      throw BookingError("Not a valid booking")
  }

  def closeBooking() = this.copy(state = CLOSED)

}
