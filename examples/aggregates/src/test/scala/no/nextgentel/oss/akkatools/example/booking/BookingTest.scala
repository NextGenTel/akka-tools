package no.nextgentel.oss.akkatools.example.booking

import java.util.UUID

import akka.actor.Status.Failure
import akka.actor.ActorSystem
import akka.testkit.{TestProbe, TestKit}
import com.typesafe.config.ConfigFactory
import no.nextgentel.oss.akkatools.example.booking.StateName._
import no.nextgentel.oss.akkatools.testing.AggregateTesting
import org.scalatest._
import org.slf4j.LoggerFactory


class BookingTest(_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {

  def this() = this(ActorSystem("test-actor-system", ConfigFactory.load("application-test.conf")))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val log = LoggerFactory.getLogger(getClass)
  private def generateId() = UUID.randomUUID().toString

  val seatIdGenerator = new StaticSeatIdGenerator()


  trait TestEnv extends AggregateTesting[BookingState] {
    val id = generateId()
    val printShop = TestProbe()
    val cinema = TestProbe()
    val main = system.actorOf(BookingAggregate.props(null, dmForwardAndConfirm(printShop.ref).path, dmForwardAndConfirm(cinema.ref).path, seatIdGenerator), "BookingAggregate-" + id)

    def assertState(correctState:BookingState): Unit = {
      assert(getState() == correctState)
    }

  }



  test("normal flow") {

    new TestEnv {
      // Make sure we start with empty state
      assertState(BookingState.empty())

      val maxSeats = 2
      val sender = TestProbe()
      // Open the booking
      sendDMBlocking(main, OpenBookingCmd(id, maxSeats), sender.ref)
      assertState(BookingState(OPEN, maxSeats, Set()))

      // send first booking
      seatIdGenerator.setNext("s1")
      sendDMBlocking(main, ReserveSeatCmd(id), sender.ref)
      assertState(BookingState(OPEN, maxSeats, Set("s1")))
      sender.expectMsg("s1") // make sure we got the seatId back

      printShop.expectMsg(PrintTicketMessage("s1")) // make sure the ticket was sent for printing

      // send another booking
      seatIdGenerator.setNext("s2")
      sendDMBlocking(main, ReserveSeatCmd(id), sender.ref)
      assertState(BookingState(OPEN, maxSeats, Set("s1", "s2")))
      sender.expectMsg("s2") // make sure we got the seatId back

      printShop.expectMsg(PrintTicketMessage("s2")) // make sure the ticket was sent for printing

      // make another booking which should fail
      seatIdGenerator.setNext("will be discarded")
      sendDMBlocking(main, ReserveSeatCmd(id), sender.ref)
      // make sure our state has not been changed
      assertState(BookingState(OPEN, maxSeats, Set("s1", "s2")))

      // and we should get an error back to sender
      assert(sender.expectMsgAnyClassOf(classOf[Failure]).cause.getMessage == "No more seats available")

      // Let's cancel the first booking
      sendDMBlocking(main, CancelSeatCmd(id, "s1"), sender.ref)
      assertState(BookingState(OPEN, maxSeats, Set("s2")))
      sender.expectMsg("ok")

      // make another bocking that should work
      seatIdGenerator.setNext("s4")
      sendDMBlocking(main, ReserveSeatCmd(id), sender.ref)
      assertState(BookingState(OPEN, maxSeats, Set("s2", "s4")))
      sender.expectMsg("s4") // make sure we got the seatId back

      printShop.expectMsg(PrintTicketMessage("s4")) // make sure the ticket was sent for printing

      // close the booking
      sendDMBlocking(main, CloseBookingCmd(id), sender.ref)
      assertState(BookingState(CLOSED, maxSeats, Set("s2", "s4")))

      // make sure the the cinema got its notice
      cinema.expectMsg(CinemaNotification(List("s2", "s4")))


      // Make a booking after it has been closed - will fail
      seatIdGenerator.setNext("will be discarded")
      sendDMBlocking(main, ReserveSeatCmd(id), sender.ref)
      // make sure our state has not been changed
      assertState(BookingState(CLOSED, maxSeats, Set("s2", "s4")))

      // and we should get an error back to sender
      assert(sender.expectMsgAnyClassOf(classOf[Failure]).cause.getMessage == "Booking is closed")

    }
  }

  class StaticSeatIdGenerator extends SeatIdGenerator {
    private var nextSeatId:Option[String] = None

    def setNext(id:String): Unit = {
      nextSeatId = Some(id)
    }

    override def generateNextSeatId(): String = {
      val id = nextSeatId.getOrElse(throw new Exception("No next seatId is available"))
      nextSeatId = None
      id
    }
  }

}



