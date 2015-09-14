package no.nextgentel.oss.akkatools.example2

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Props, ActorLogging, Actor, ActorSystem}
import akka.util.Timeout
import akka.pattern.ask
import no.nextgentel.oss.akkatools.example2.other.{TrustAccountProcessingSystem, ESigningSystem, EmailSystem}
import no.nextgentel.oss.akkatools.example2.trustaccountcreation._
import no.nextgentel.oss.akkatools.persistence.{EventAndState, GetEventAndStateHistory, DurableMessage}

import scala.concurrent.Await
import scala.io.StdIn._
import scala.util.Random


object Example2App extends App {
  val infoColor = Console.YELLOW
  val e = new Example2System()

  val tacId = "tac-" + UUID.randomUUID().toString

  Thread.sleep(3000)

  doNextStep("Creating TrustAccount") {
    () =>
        val r = e.createNewTACCmd(tacId)
        printInfo(s"Our create-client got: $r")
  }

  doNextStep("Creating the same TrustAccount again - should fail") {
    () =>
      try {
        e.createNewTACCmd(tacId)
      } catch {
        case e: Exception => {
          waitForALittleWhile()
          printInfo("Our create-client got error: " + e.getMessage)
        }
      }
  }



  doNextStep("Complete the E-Signing") {
    () =>
      e.completeESigning(tacId)
  }

  doNextStep("Complete the creation of the TrustAccount") {
    () =>
      e.completeTrustAccountCreation(tacId)
  }


  doNextStep("Printing history of the booking") {
    () =>
      printHistory(tacId)
  }


  doNextStep("quit") {
    () =>
      System.exit(10)
  }


  def printInfo(info:String, sleep:Int = 1): Unit ={
    Thread.sleep(sleep*1000)
    println(infoColor + info +  Console.RESET)
  }

  def waitForALittleWhile(seconds:Int = 1): Unit ={
    Thread.sleep(seconds*1000)
  }

  def doNextStep[T](description:String)(work:()=>T): T = {
    Thread.sleep(1000)
    println(Console.BLUE + "\n\nType 'run + [ENTER]' to execute next step: " + infoColor + description + Console.RESET)
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

    e.getTACHistory(bookingId).foreach {
      e => printInfo("Event: " + e.event.toString, 0)

    }
  }

}

class Example2System(system: ActorSystem) {

  def this() = this(ActorSystem("ExampleSystem"))

  implicit val ec = system.dispatcher
  implicit val timeout = Timeout(20, TimeUnit.SECONDS)

  private val tac = new TACAggregateBuilder(system)

  private val emailSystem = system.actorOf(Props(new EmailSystem), "email-system")
  private val eSigningSystem = system.actorOf(Props(new ESigningSystem(tac.dispatcher)), "E-SigningSystem")
  private val trustAccountProcessingSystem = system.actorOf(Props(new TrustAccountProcessingSystem(tac.dispatcher)), "TrustAccountProcessingSystem")

  tac.config(eSigningSystem.path, emailSystem.path, trustAccountProcessingSystem.path)

  tac.start()

  def createNewTACCmd(id: String): String = {
    val info = TrustAccountCreationInfo("Customer-1", "TA-X")
    val msg = CreateNewTACCmd(id, info)
    Await.result(ask(tac.dispatcher, msg), timeout.duration).asInstanceOf[String]
  }

  def completeESigning(id:String): Unit = {
    tac.dispatcher ! ESigningCompletedCmd(id)
  }

  def completeTrustAccountCreation(id:String): Unit = {
    val trustAccountId = "TA-"+new Random().nextInt(9999)
    tac.dispatcher ! CompletedCmd(id, trustAccountId)
  }
/*
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
*/

  def getTACHistory(id:String):List[EventAndState] = {
    val f = tac.askView(id, GetEventAndStateHistory()).mapTo[List[EventAndState]]
    Await.result(f, timeout.duration)
  }
}




