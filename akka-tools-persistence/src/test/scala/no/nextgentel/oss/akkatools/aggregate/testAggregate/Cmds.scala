package no.nextgentel.oss.akkatools.aggregate.testAggregate

import no.nextgentel.oss.akkatools.aggregate.AggregateCmd

// Commands
trait BookingCmd extends AggregateCmd {
  def bookingId(): String

  override def id(): String = bookingId()
}

case class OpenBookingCmd(bookingId: String, seats: Int) extends BookingCmd

case class ReserveSeatCmd(bookingId: String) extends BookingCmd

case class CancelSeatCmd(bookingId: String, seatId: String) extends BookingCmd

case class CloseBookingCmd(bookingId: String) extends BookingCmd
