package no.ngt.oss.akkatools.example

import akka.actor.{Props, ActorLogging, Actor, ActorSystem}
import com.typesafe.config.ConfigFactory
import no.ngt.oss.akkatools.cluster.{NgtClusterConfig, NgtClusterListener}
import no.ngt.oss.akkatools.persistence.DurableMessage
import org.slf4j.LoggerFactory


object ExampleApp extends App {

  val log = LoggerFactory.getLogger(getClass)

  val e = new ExampleSystem()

  val bookingId = "movie-1"

  Thread.sleep(1500)
  log.info("Placing booking")
  e.placeBooking(bookingId)


  Thread.sleep(100000)


}

class ExampleSystem(system:ActorSystem) {

  def this() = this(ActorSystem("ExampleSystem"))


  private val booking = new BookingAggregateBuilder(system)

  val bookingDispatcher = booking.dispatcher

  private val cinema = system.actorOf(Props(new Cinema), "cinema")
  private val ticketPrinter = system.actorOf(Props(new TicketPrinter), "ticketPrintger")

  booking.config(ticketPrinter.path, cinema.path)

  booking.start()

  def placeBooking(bookingId:String): Unit = {
    val msg = ReserveSeatCmd(bookingId)
    bookingDispatcher ! msg
  }

}

class Cinema extends Actor with ActorLogging {

  def receive = {
    case dm:DurableMessage =>
      val m = dm.payload
      log.info(s"Cinema: $m")
      dm.confirm(context, self)
  }
}

class TicketPrinter extends Actor with ActorLogging {

  def receive = {
    case dm:DurableMessage =>
      val m = dm.payload
      log.info(s"Printing ticket: $m")
      dm.confirm(context, self)
  }
}
