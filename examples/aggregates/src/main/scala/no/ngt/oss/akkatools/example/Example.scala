package no.ngt.oss.akkatools.example

import java.util.concurrent.TimeUnit

import akka.actor.{Props, ActorLogging, Actor, ActorSystem}
import akka.util.Timeout
import akka.pattern.ask
import no.ngt.oss.akkatools.cluster.{NgtClusterConfig, NgtClusterListener}
import no.ngt.oss.akkatools.persistence.DurableMessage
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.util.Random


object ExampleApp extends App {

  val log = LoggerFactory.getLogger(getClass)

  val e = new ExampleSystem()

  val bookingId = "movie-1"

  Thread.sleep(3500)
  log.info("\n\n\n\n******************************************")
  log.info("Placing booking - which will fail")

  try {
    // This will fail
    e.placeBooking(bookingId)
  } catch {
    case e: Exception => log.info("Ignoring booking-error: " + e.getMessage)
  }

  Thread.sleep(3500)
  log.info("\n\n\n\n******************************************")
  log.info("Open the Booking")
  e.openBooking(bookingId, 3)

  Thread.sleep(3500)
  log.info("\n\n\n\n******************************************")
  log.info("Placing booking")
  val seatId = e.placeBooking(bookingId)
  log.info(s"Booking completed - seatId: $seatId")

  Thread.sleep(3500)
  log.info("\n\n\n\n******************************************")
  log.info("Placing Another booking")
  val seatId2 = e.placeBooking(bookingId)
  log.info(s"Booking completed - seatId: $seatId2")

  Thread.sleep(4500)
  log.info("\n\n\n\n******************************************")
  log.info("Close booking")
  e.closeBooking(bookingId)


  Thread.sleep(100000)


}

class ExampleSystem(system: ActorSystem) {

  def this() = this(ActorSystem("ExampleSystem"))

  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(20, TimeUnit.SECONDS)

  private val booking = new BookingAggregateBuilder(system)

  val bookingDispatcher = booking.dispatcher

  private val cinema = system.actorOf(Props(new Cinema), "cinema")
  private val ticketPrinter = system.actorOf(Props(new TicketPrinter), "ticketPrintger")

  booking.config(ticketPrinter.path, cinema.path)

  booking.start()

  def placeBooking(bookingId: String): String = {
    val msg = ReserveSeatCmd(bookingId)
    Await.result(ask(bookingDispatcher, msg), timeout.duration).asInstanceOf[String]
  }

  def openBooking(bookingId:String, seats:Int): Unit = {
    bookingDispatcher ! OpenBookingCmd(bookingId, seats)
  }

  def closeBooking(bookingId:String): Unit ={
    bookingDispatcher ! CloseBookingCmd(bookingId)
  }

}

class Cinema extends Actor with ActorLogging {

  def receive = {
    case dm: DurableMessage =>
      val m = dm.payload
      log.info(s"Cinema: $m")
      dm.confirm(context, self)
  }
}

class TicketPrinter extends Actor with ActorLogging {

  var counter = 0

  def receive = {
    case dm: DurableMessage =>
      val m = dm.payload
      counter = counter + 1
      val willCrash = (counter % 3) != 0

      if ( willCrash)
        log.warning(s"Failing to print ticket $m")
      else {

        log.info(s"Printing ticket: $m")
        dm.confirm(context, self)
      }
  }
}
