package no.ngt.oss.akkatools.example

import java.util.UUID

import akka.actor.Status.Failure
import akka.actor.{Props, ActorSystem, ActorRef, ActorPath}
import no.ngt.oss.akkatools.aggregate._

// Commands
trait BookingCmd extends AggregateCmd {
  def bookingId():String

  override def id(): String = bookingId()
}

case class OpenBookingCmd(bookingId:String, seats:Int) extends BookingCmd
case class ReserveSeatCmd(bookingId:String) extends BookingCmd
case class CancelSeatCmd(bookingId:String, seatId:String) extends BookingCmd
case class CloseBookingCmd(bookingId:String) extends BookingCmd

// CommandResults
case class BookingConfirmed(id:String)

// Events

trait BookingEvent

case class BookingOpenEvent(numberOfFreeSeats:Int) extends BookingEvent
case class ReservationEvent(id:String)   extends BookingEvent
case class CancelationEvent(id:String)    extends BookingEvent
case class BookingClosedEvent() extends BookingEvent


case class BookingError(e:String) extends AggregateError(e)

// State (machine)

object BookingState {
  def empty() = BookingState(false, 0, Set(), false)
}

case class BookingState
(
  opened:Boolean,
  seats:Int,
  reservations:Set[String],
  closed:Boolean
  ) extends AggregateState[BookingEvent, BookingState] {

  override def transition(event: BookingEvent): BookingState = {
    if(!opened) {
      // The only valid event now is to open the booking
      event match {
        case e:BookingOpenEvent =>
          // we're opening the booking
          BookingState(true, e.numberOfFreeSeats, Set(), false)
        case e:BookingEvent =>
          throw BookingError("Invalid event since Booking is not opened yet")
      }
    } else {
      // Booking has been opened, but is it closed?
      if (closed) throw BookingError("Booking is close")
      event match {
        case e:ReservationEvent =>
          // We must try to book
          if (reservations.size >= seats) {
            throw BookingError("No more seats available")
          } else {
            // Add id to reservation-list
            copy( reservations = reservations + e.id)
          }
        case e:CancelationEvent =>
          // Must verify that this is a valid booking to cancel
          if (reservations.contains(e.id)) {
            // Remove the booing
            copy(reservations = reservations - e.id)
          } else {
            throw BookingError("Not a valid booking")
          }
        case e:BookingClosedEvent =>
          // Closing the booking
          copy( closed = true)
        case e:BookingEvent =>
          throw BookingError("Not a valid event for this open booking")
      }
    }
  }
}

object BookingAggregate {
  def props(ourDispatcherActor:ActorPath, ticketPrintShop:ActorPath, cinemaNotifier:ActorPath) =
    Props(new BookingAggregate(ourDispatcherActor, ticketPrintShop, cinemaNotifier))
}

// Aggregate
class BookingAggregate(ourDispatcherActor:ActorPath, ticketPrintShop:ActorPath, cinemaNotifier:ActorPath)
  extends GeneralAggregate[BookingEvent, BookingState](ourDispatcherActor) {

  var state = BookingState.empty()

  override def cmdToEvent = {
    case c:OpenBookingCmd  =>     EventResult( BookingOpenEvent(c.seats) )
    case c:CloseBookingCmd =>     EventResult( BookingClosedEvent() )
    case c:ReserveSeatCmd  =>
      val seatId = UUID.randomUUID().toString
      EventResult(ReservationEvent(seatId), {
        errorMsg =>
          sender ! Failure(new Exception("Sorry - booking not possible: " + errorMsg))
      })
    case c:CancelSeatCmd =>       EventResult( CancelationEvent(c.seatId) )
  }

  override def generateExternalEffects = {
    case e:BookingClosedEvent =>
      val cinemaNotification = "Booking has closed with " + state.reservations.size + " seats reserved"
      ExternalEffects(cinemaNotification, cinemaNotifier)

    case e:ReservationEvent =>
      // We shoud send booking-confirmation and ticket should be printed

      val printShopMessage = "Ticket " + e.id + " is a valid ticket!"
      ExternalEffects(printShopMessage, ticketPrintShop)
  }
}

class BookingAggregateBuilder(actorSystem:ActorSystem) extends GeneralAggregateBuilder[BookingEvent, BookingState](actorSystem, "booking", Some(BookingState.empty())) {

  def config(ticketPrintShop:ActorPath, cinemaNotifier:ActorPath): Unit = {
    withGeneralAggregateProps {
      ourDispatcher:ActorPath =>
        BookingAggregate.props(ourDispatcher, ticketPrintShop, cinemaNotifier)
    }
  }

}