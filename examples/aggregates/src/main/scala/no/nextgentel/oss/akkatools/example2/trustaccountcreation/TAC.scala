package no.nextgentel.oss.akkatools.example2.trustaccountcreation

import java.util.concurrent.TimeUnit

import akka.actor.Status.Failure
import akka.actor.{ActorSystem, Props, ActorPath}
import no.nextgentel.oss.akkatools.aggregate._
import no.nextgentel.oss.akkatools.example2.other.{DoCreateTrustAccount, DoPerformESigning, DoSendEmailToCustomer}

import scala.concurrent.duration.FiniteDuration

class TACAggregate
(
  ourDispatcher:ActorPath,
  eSigningSystem:ActorPath,
  emailSystem:ActorPath,
  trustAccountSystem:ActorPath
) extends GeneralAggregate[TACEvent, TACState](FiniteDuration(60, TimeUnit.SECONDS), ourDispatcher) {


  override var state = TACState.empty() // This is the state of our initial state (empty)

  // transform command to event
  override def cmdToEvent = {
    case c:CreateNewTACCmd        =>
      ResultingEvent( RegisteredEvent(c.info) )
        .withSuccessHandler{ () => sender() ! "ok" }
        .withErrorHandler{   (e) => sender() ! Failure(new Exception(s"Failed: $e"))}

    case c:ESigningFailedCmd      => ResultingEvent( ESigningFailedEvent() )
    case c:ESigningCompletedCmd   => ResultingEvent( ESigningCompletedEvent() )
    case c:CompletedCmd           => ResultingEvent( CreatedEvent(c.trustAccountId) )
    case c:DeclinedCmd            => ResultingEvent( DeclinedEvent(c.cause) )
  }

  override def generateResultingDurableMessages = {
    case e:RegisteredEvent  =>
      // We must send message to eSigningSystem
      val msg = DoPerformESigning(dispatchId, e.info.customerNo)
      ResultingDurableMessages( msg, eSigningSystem)

    case e:ESigningCompletedEvent =>
      // ESigning is completed, so we should init creation of the TrustAccount
      val info = state.info.get
      val msg = DoCreateTrustAccount(dispatchId, info.customerNo, info.trustAccountType)
      ResultingDurableMessages(msg, trustAccountSystem)


    case e:DeclinedEvent =>
      // The TrustAccountCreation-process failed - must notify customer
      val msg = DoSendEmailToCustomer(state.info.get.customerNo, s"Sorry.. TAC-failed: ${e.cause}")
      ResultingDurableMessages(msg, emailSystem)

    case e:CreatedEvent =>
      // The TrustAccountCreation-process was success - must notify customer
      val msg = DoSendEmailToCustomer(state.info.get.customerNo, s"Your TrustAccount '${e.trustAccountId}' has been created!")
      ResultingDurableMessages(msg, emailSystem)

  }
}

object TACAggregate {

  def props(ourDispatcher:ActorPath,
            eSigningSystem:ActorPath,
            emailSystem:ActorPath,
            trustAccountSystem:ActorPath) = Props(new TACAggregate(ourDispatcher, eSigningSystem, emailSystem ,trustAccountSystem))
}


// Setting up the builder we're going to use for our BookingAggregate and view
class TACAggregateBuilder(actorSystem: ActorSystem) extends GeneralAggregateBuilder[TACEvent, TACState](actorSystem, "tac", Some(TACState.empty())) {

  def config(eSigningSystem:ActorPath,
             emailSystem:ActorPath,
             trustAccountSystem:ActorPath): Unit = {
    withGeneralAggregateProps {
      ourDispatcher: ActorPath =>
        TACAggregate.props(ourDispatcher, eSigningSystem, emailSystem, trustAccountSystem)
    }
  }

}