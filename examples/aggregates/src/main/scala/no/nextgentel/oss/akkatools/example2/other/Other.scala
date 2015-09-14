package no.nextgentel.oss.akkatools.example2.other

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorLogging, Actor}
import no.nextgentel.oss.akkatools.example2.trustaccountcreation.{CompletedCmd, CreateNewTACCmd, ESigningCompletedCmd}
import no.nextgentel.oss.akkatools.persistence.DurableMessage

import scala.concurrent.duration.{FiniteDuration, Duration}
import scala.util.Random


// E-Mail
case class DoSendEmailToCustomer(customerNo:String, message:String)

class EmailSystem extends Actor with ActorLogging {

  var counter = 0

  def receive = {
    case dm:DurableMessage =>
      val m = dm.payloadAs[DoSendEmailToCustomer]()
      if ( shouldFail()) {
        log.error(Console.RED+s"EmailSystem: Failed to receive: $m"+Console.RESET)
      } else {
        log.info(Console.GREEN + s"EmailSystem: Sending: $m"+Console.RESET)
        dm.confirm(context, self)
      }
  }

  def shouldFail():Boolean = {
    counter = counter + 1
    if ( (counter % 3) == 0 ) false else true
  }
}

// E-Signing-system
case class DoPerformESigning(tackId:String, customerNo:String)

class ESigningSystem(tacSystem:ActorRef, autoContinue:Boolean = false) extends Actor with ActorLogging {

  implicit val ec = context.dispatcher
  val delay = FiniteDuration(4, TimeUnit.SECONDS)

  var counter = 0

  case class CompleteIt(dm:DurableMessage)

  def receive = {
    case dm:DurableMessage =>
      val m = dm.payloadAs[DoPerformESigning]
      if ( autoContinue ) {
        log.info(Console.GREEN+s"E-SigningSystem: Going to complete e-signing in $delay: $m"+Console.RESET)

        context.system.scheduler.scheduleOnce(delay, self, CompleteIt(dm))
      } else {
        if ( shouldFail()) {
          log.error(Console.RED + s"E-SigningSystem: failed to receive: $m"+Console.RESET)
        } else {
          log.info(Console.GREEN + s"E-SigningSystem: Received: $m"+Console.RESET)
          dm.confirm(context, self)
        }
      }
    case CompleteIt(dm) =>
      val m = dm.payloadAs[DoPerformESigning]
      log.info(Console.GREEN+s"E-SigningSystem: Completing it now: $m"+Console.RESET)

      val completeCmd = ESigningCompletedCmd(m.tackId)
      val returnMsg = dm.withNewPayload(completeCmd)
      tacSystem ! returnMsg

  }

  def shouldFail():Boolean = {
    counter = counter + 1
    if ( (counter % 3) == 0 ) false else true
  }
}


// Trust account processing system
case class DoCreateTrustAccount(tackId:String, customerId:String, trustAccountType:String)

class TrustAccountProcessingSystem(tacSystem:ActorRef, autoContinue:Boolean = false) extends Actor with ActorLogging {

  implicit val ec = context.dispatcher
  val delay = FiniteDuration(4, TimeUnit.SECONDS)

  val random = new Random()
  var counter = 0

  case class CompleteIt(dm:DurableMessage)

  def receive = {
    case dm:DurableMessage =>
      val m = dm.payloadAs[DoCreateTrustAccount]
      if ( autoContinue ) {
        log.info(Console.GREEN+s"TrustAccountProcessingSystem: Going to Create TAC in $delay: $m"+Console.RESET)

        context.system.scheduler.scheduleOnce(delay, self, CompleteIt(dm))
      } else {
        if ( false /*shouldFail()*/) {
          log.error(Console.RED + s"TrustAccountProcessingSystem: failed to receive: $m"+Console.RESET)
        } else {
          log.info(Console.GREEN + s"TrustAccountProcessingSystem: Received: $m"+Console.RESET)
          dm.confirm(context, self)
        }
      }
    case CompleteIt(dm) =>
      val m = dm.payloadAs[DoCreateTrustAccount]
      val trustAccountId = "TA-" + random.nextInt(9999)
      log.info(Console.GREEN+s"TrustAccountProcessingSystem: Creating TAC with trustAccountId=$trustAccountId now: $m"+Console.RESET)

      val completeCmd = CompletedCmd(m.tackId, trustAccountId)
      val returnMsg = dm.withNewPayload(completeCmd)
      tacSystem ! returnMsg

  }

  def shouldFail():Boolean = {
    counter = counter + 1
    if ( (counter % 3) == 0 ) false else true
  }
}