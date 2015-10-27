package no.nextgentel.oss.akkatools.example.booking

import org.scalatest.{FunSuite, Matchers}

class BookingStateTest extends FunSuite with Matchers {

  test("Booking before open should fail") {
    val s = BookingState.empty()

    val e = ReservationEvent("id1")

    val error = intercept[BookingError] {
      s.transition(e)
    }
    assert(error.getMessage == "Invalid event since Booking is not opened yet")
  }

  test("Normal flow") {
    var s = BookingState.empty()

    s = s.transition(BookingOpenEvent(2))
    s = s.transition(ReservationEvent("1"))
    s = s.transition(ReservationEvent("2"))

    // This one should fail - no more roome
    val error = intercept[BookingError] {
      s.transition(ReservationEvent("2"))
    }
    assert(error.getMessage == "No more seats available")

    s = s.transition(CancellationEvent("1"))
    s = s.transition(ReservationEvent("3"))
    s = s.transition(BookingClosedEvent())

    assert( s == BookingState(StateName.CLOSED, 2, Set("2", "3")))


  }

}
