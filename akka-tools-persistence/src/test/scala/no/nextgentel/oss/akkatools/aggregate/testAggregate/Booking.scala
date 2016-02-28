package no.nextgentel.oss.akkatools.aggregate.testAggregate

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.Status.Failure
import akka.actor.{ActorPath, ActorSystem, Props}
import no.nextgentel.oss.akkatools.aggregate._

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration


case class PrintTicketMessage(id:String)
case class CinemaNotification(seatsBooked:List[String])

// Aggregate
class BookingAggregate(dmSelf: ActorPath, ticketPrintShop: ActorPath, cinemaNotifier: ActorPath, var predefinedSeatIds:List[String], onSuccessDmForwardReceiver:ActorPath)
  extends GeneralAggregate[BookingEvent, BookingState](dmSelf) {


  // Used as prefix/base when constructing the persistenceId to use - the unique ID is extracted runtime from actorPath which is construced by Sharding-coordinator
  override def persistenceIdBase() = BookingAggregate.persistenceIdBase

  var state = BookingState.empty() // This is our initial state(Machine)

  def generateNextSeatId():String = {

    if (predefinedSeatIds.isEmpty) {
      UUID.randomUUID().toString
    } else {
      // pop the first id
      val id = predefinedSeatIds(0)
      predefinedSeatIds = predefinedSeatIds.tail
      id
    }
  }

  // transform command to event
  override def cmdToEvent = {
    case c: OpenBookingCmd  =>  ResultingEvent(BookingOpenEvent(c.seats))
      .onSuccess {
        sendAsDurableMessage("OpenBookingCmd-ok", onSuccessDmForwardReceiver)
      }

    case c: CloseBookingCmd => ResultingEvent(BookingClosedEvent())

    case c: ReserveSeatCmd  =>
      // Generate a random seatId
      val seatId = generateNextSeatId()
      val event = ReservationEvent(seatId)

      ResultingEvent(event)
        .onSuccess{
          sender ! seatId } // Send the seatId back
        .onError ( errorMsg => sender ! Failure(new Exception(errorMsg)) )
        .onAfterValidationSuccess{
          if (c.shouldFailIn_onAfterValidationSuccess) {
            throw BookingError("Failed in onAfterValidationSuccess")
          }
        }

    case c: CancelSeatCmd =>
      ResultingEvent(CancelationEvent(c.seatId))
        .onSuccess( sender ! "ok")
        .onError( (errorMsg) => sender ! Failure(new Exception(errorMsg)) )

    case c: CmdThatFailsWhenGeneratingEvent =>
      ResultingEvent {
          println("This is executed later - when our onError-code is ready to be used")
          throw BookingError("The error is: " + c.error)
        }.onSuccess(sender ! "ok")
        .onError((errorMsg) => sender ! Failure(new Exception(errorMsg)))
  }

  override def generateResultingDurableMessages = {
    case e: BookingClosedEvent =>

      // We're just testing the nextState()-functionality
      assert( nextState() == state.transition(e))

      // The booking has now been closed and we need to send an important notification to the Cinema
      val cinemaNotification = CinemaNotification(state.reservations.toList)
      ResultingDurableMessages(cinemaNotification, cinemaNotifier)

    case e: ReservationEvent =>
      // The seat-reservation has been confirmed and we need to print the ticket

      val printShopMessage = PrintTicketMessage(e.id)
      ResultingDurableMessages(printShopMessage, ticketPrintShop)
  }
}



object BookingAggregate {

  val persistenceIdBase = "booking-"

  def props(dmSelf: ActorPath, ticketPrintShop: ActorPath, cinemaNotifier: ActorPath, predefinedSeatIds:List[String], onSuccessDmForwardReceiver:ActorPath) =
    Props(new BookingAggregate(dmSelf, ticketPrintShop, cinemaNotifier, predefinedSeatIds, onSuccessDmForwardReceiver))
}


class BookingStarter(system:ActorSystem) extends AggregateStarter("booking", system) with AggregateViewStarter {

  def config(ticketPrintShop: ActorPath, cinemaNotifier: ActorPath, predefinedSeatIds:List[String], onSuccessDmForwardReceiver:ActorPath):BookingStarter = {
    setAggregatePropsCreator{
      dmSelf =>
        BookingAggregate.props(dmSelf, ticketPrintShop, cinemaNotifier, predefinedSeatIds, onSuccessDmForwardReceiver)
    }
    this
  }

  override def createViewProps(aggregateId: String): Props =
    Props( new GeneralAggregateView[BookingEvent, BookingState](BookingAggregate.persistenceIdBase, aggregateId, BookingState.empty(), true))
}
