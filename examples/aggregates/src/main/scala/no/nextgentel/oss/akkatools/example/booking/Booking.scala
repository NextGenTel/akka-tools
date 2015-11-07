package no.nextgentel.oss.akkatools.example.booking

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.Status.Failure
import akka.actor.{ActorPath, ActorSystem, Props}
import no.nextgentel.oss.akkatools.aggregate._

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration


case class PrintTicketMessage(id:String)
case class CinemaNotification(seatsBooked:List[String])


// Aggregate
class BookingAggregate(ourDispatcherActor: ActorPath, ticketPrintShop: ActorPath, cinemaNotifier: ActorPath, seatIdGenerator: SeatIdGenerator)
  extends GeneralAggregate[BookingEvent, BookingState](ourDispatcherActor) {


  var state = BookingState.empty() // This is our initial state(Machine)


  // transform command to event
  override def cmdToEvent = {
    case c: OpenBookingCmd  => ResultingEvent(BookingOpenedEvent(c.seats))

    case c: CloseBookingCmd => ResultingEvent(BookingClosedEvent())

    case c: ReserveSeatCmd  =>
      // Generate a random seatId
      val seatId = seatIdGenerator.generateNextSeatId()
      val event = SeatReservedEvent(seatId)

      ResultingEvent(event)
        .onSuccess( sender ! seatId ) // Send the seatId back
        .onError ( errorMsg => sender ! Failure(new Exception(errorMsg)) )

    case c: CancelSeatCmd =>
      ResultingEvent(SeatCancelledEvent(c.seatId))
        .onSuccess( sender ! "ok")
        .onError( (errorMsg) => sender ! Failure(new Exception(errorMsg)) )
  }

  override def generateResultingDurableMessages = {
    case e: BookingClosedEvent =>
      // The booking has now been closed and we need to send an important notification to the Cinema
      val msg = CinemaNotification(state.reservations.toList)
      ResultingDurableMessages(msg, cinemaNotifier)

    case e: SeatReservedEvent =>
      // The seat-reservation has been confirmed and we need to print the ticket

      val msg = PrintTicketMessage(e.id)
      ResultingDurableMessages(msg, ticketPrintShop)
  }

  override def persistenceIdBase() = BookingAggregate.persistenceIdBase

  // Override this one to set different timeout
  override def idleTimeout() = FiniteDuration(60, TimeUnit.SECONDS)

}



object BookingAggregate {

  val persistenceIdBase = "booking-"

  def props(ourDispatcherActor: ActorPath, ticketPrintShop: ActorPath, cinemaNotifier: ActorPath, seatIdGenerator: SeatIdGenerator = new DefaultSeatIdGenerator()) =
    Props(new BookingAggregate(ourDispatcherActor, ticketPrintShop, cinemaNotifier, seatIdGenerator))
}


trait SeatIdGenerator {
  def generateNextSeatId():String
}

class DefaultSeatIdGenerator extends SeatIdGenerator {
  private val nextId = new AtomicInteger(0)
  override def generateNextSeatId(): String = "seat-"+nextId.incrementAndGet()
}


// Setting up the builder we're going to use for our BookingAggregate and view
class BookingAggregateBuilder(actorSystem: ActorSystem) extends GeneralAggregateBuilder[BookingEvent, BookingState](actorSystem) {


  override def persistenceIdBase() = BookingAggregate.persistenceIdBase

  def config(ticketPrintShop: ActorPath, cinemaNotifier: ActorPath): Unit = {
    withGeneralAggregateProps {
      ourDispatcher: ActorPath =>
        BookingAggregate.props(ourDispatcher, ticketPrintShop, cinemaNotifier)
    }
  }

  // Override this method to create Initial states for views
  override def createInitialState(aggregateId: String): BookingState = BookingState.empty()
}