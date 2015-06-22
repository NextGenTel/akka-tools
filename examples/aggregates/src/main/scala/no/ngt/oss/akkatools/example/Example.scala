package no.ngt.oss.akkatools.example

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Props, ActorLogging, Actor, ActorSystem}
import akka.util.Timeout
import akka.pattern.ask
import no.ngt.oss.akkatools.cluster.{NgtClusterConfig, NgtClusterListener}
import no.ngt.oss.akkatools.persistence.{EventAndState, GetEventAndStateHistory, DurableMessage}
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.io.StdIn._


object ExampleApp extends App {

  val e = new ExampleSystem()

  val bookingId = "movie-" + UUID.randomUUID().toString

  Thread.sleep(3000)

  doNextStep("Placing booking - which will fail") {
    () =>
      try {
        // This will fail
        e.placeBooking(bookingId)
      } catch {
        case e: Exception => {
          waitForALittleWhile()
          printInfo("Ignoring booking-error: " + e.getMessage)
        }
      }
  }

  doNextStep("Open the booking") {
    () =>
      e.openBooking(bookingId, 3)
  }


  val firstSeatId:String = doNextStep("Place booking") {
    () =>
      val seatId = e.placeBooking(bookingId)
      printInfo(s"Booking completed - seatId: $seatId")
      waitForALittleWhile(15)
      seatId
  }

  doNextStep("Place another booking") {
    () =>
      val seatId = e.placeBooking(bookingId)
      printInfo(s"Booking completed - seatId: $seatId")
      waitForALittleWhile(15)
  }

  doNextStep("Cancel an invalid booking") {
    () =>
      try {
        // This will fail
        e.cancelBooking(bookingId, "seat-na")
      } catch {
        case e: Exception => {
          waitForALittleWhile()
          printInfo("Ignoring booking-error: " + e.getMessage)
        }
      }
  }

  doNextStep("Cancel the first booking") {
    () =>
      e.cancelBooking(bookingId, firstSeatId)
      printInfo(s"Seat canceled")
      waitForALittleWhile()
  }

  doNextStep("Close booking") {
    () =>
      e.closeBooking(bookingId)
  }

  doNextStep("Try a booking after it has been closed - will fail") {
    () =>
      try {
        // This will fail
        e.placeBooking(bookingId)
      } catch {
        case e: Exception => {
          waitForALittleWhile()
          printInfo("Ignoring booking-error: " + e.getMessage)
        }
      }
  }

  doNextStep("Printing history of the booking") {
    () =>
      printHistory(bookingId)
  }


  doNextStep("quit") {
    () =>
      System.exit(10)
  }


  def printInfo(info:String, sleep:Int = 1): Unit ={
    Thread.sleep(sleep*1000)
    println(Console.YELLOW + info +  Console.RESET)
  }

  def waitForALittleWhile(seconds:Int = 1): Unit ={
    Thread.sleep(seconds*1000)
  }

  def doNextStep[T](description:String)(work:()=>T): T = {
    Thread.sleep(1000)
    println(Console.BLUE + "\n\nType 'run + [ENTER]' to execute next step: " + Console.YELLOW + description + Console.RESET)
    var wait = true
    while(wait) {
      val line = readLine()
//      println("line: " + line)
      if (line != null && line.equalsIgnoreCase("run")) {
        wait = false
      }
    }
    work.apply()

  }

  def printHistory(bookingId:String): Unit = {

    e.getBookingHistory(bookingId).foreach {
      e => printInfo("Event: " + e.event.toString, 0)

    }
  }

}

class ExampleSystem(system: ActorSystem) {

  def this() = this(ActorSystem("ExampleSystem"))

  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(20, TimeUnit.SECONDS)

  private val booking = new BookingAggregateBuilder(system)

  private val cinema = system.actorOf(Props(new Cinema), "cinema")
  private val ticketPrinter = system.actorOf(Props(new TicketPrinter), "ticketPrintger")

  booking.config(ticketPrinter.path, cinema.path)

  booking.start()

  def placeBooking(bookingId: String): String = {
    val msg = ReserveSeatCmd(bookingId)
    Await.result(ask(booking.dispatcher, msg), timeout.duration).asInstanceOf[String]
  }

  def openBooking(bookingId:String, seats:Int): Unit = {
    booking.dispatcher ! OpenBookingCmd(bookingId, seats)
  }

  def cancelBooking(bookingId:String, seatId:String): Unit = {
    val msg = CancelSeatCmd(bookingId, seatId)
    Await.result(ask(booking.dispatcher, msg), timeout.duration)
  }

  def closeBooking(bookingId:String): Unit = {
    booking.dispatcher ! CloseBookingCmd(bookingId)
  }

  def getBookingHistory(bookingId:String):List[EventAndState] = {
    val f = booking.askView(bookingId, GetEventAndStateHistory()).mapTo[List[EventAndState]]
    Await.result(f, timeout.duration)
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
