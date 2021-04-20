package no.nextgentel.oss.akkatools.example.booking

import org.scalatest.funsuite.AnyFunSuite


class BookingStateTest extends AnyFunSuite {

  test("Booking before open should fail") {
    val s = BookingState.empty()

    val e = SeatReservedEvent("id1")

    val error = intercept[BookingError] {
      s.transition(e)
    }
    assert(error.getMessage == "Invalid event since Booking is not opened yet")
  }

  test("Normal flow") {
    var s = BookingState.empty()

    s = s.transition(BookingOpenedEvent(2))
    s = s.transition(SeatReservedEvent("1"))
    s = s.transition(SeatReservedEvent("2"))

    // This one should fail - no more roome
    val error = intercept[BookingError] {
      s.transition(SeatReservedEvent("2"))
    }
    assert(error.getMessage == "No more seats available")

    s = s.transition(SeatCancelledEvent("1"))
    s = s.transition(SeatReservedEvent("3"))
    s = s.transition(BookingClosedEvent())

    assert( s == BookingState(StateName.CLOSED, 2, Set("2", "3")))


  }

}
