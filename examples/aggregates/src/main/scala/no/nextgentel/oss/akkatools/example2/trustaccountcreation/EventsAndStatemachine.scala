package no.nextgentel.oss.akkatools.example2.trustaccountcreation

import no.nextgentel.oss.akkatools.aggregate.{AggregateState, AggregateError}

// Events



trait TACEvent

case class TrustAccountCreationInfo(customerNo:String, trustAccountType:String)

case class RegisteredEvent(info:TrustAccountCreationInfo)      extends TACEvent
case class ESigningFailedEvent()                               extends TACEvent
case class ESigningCompletedEvent()                            extends TACEvent
case class CreatedEvent(trustAccountId:String)                 extends TACEvent
case class DeclinedEvent(cause:String)                         extends TACEvent

// Generic TAC-Error
case class TACError(e: String) extends AggregateError(e)


object StateName extends Enumeration {
  type StateName = Value
  val INITIAL_STATE = Value("INITIAL_STATE")
  val PENDING_E_SIGNING = Value("Pending_E_Signing")
  val PROCESSING = Value("Processing")
  val DECLINED = Value("Declined")
  val CREATED = Value("Created")
}

import StateName._

object TACState {
  def empty() = TACState(INITIAL_STATE, None, None, None)
}

case class TACState
(
  state:StateName,
  info:Option[TrustAccountCreationInfo],
  trustAccountId:Option[String],
  declineCause:Option[String]
  ) extends AggregateState[TACEvent, TACState] {

  override def transition(event: TACEvent) = {
    (state, event) match {
      case (INITIAL_STATE,     e:RegisteredEvent)        => TACState(PENDING_E_SIGNING, Some(e.info), None, None)
      case (_,                 e:RegisteredEvent)        => throw TACError("Cannot re-create this TAC") // custom error
      case (PENDING_E_SIGNING, e:ESigningFailedEvent)    => copy( state = DECLINED, declineCause = Some("E-Signing failed") )
      case (PENDING_E_SIGNING, e:ESigningCompletedEvent) => copy( state = PROCESSING )
      case (PROCESSING,        e:DeclinedEvent)          => copy( state = DECLINED, declineCause = Some(e.cause) )
      case (PROCESSING,        e:CreatedEvent)           => TACState( CREATED, info, Some(e.trustAccountId), None )

      case (s, e:AnyRef) =>
        val eventName = e.getClass.getSimpleName
        throw TACError(s"Current state is '$s'. Got invalid event: $eventName")
    }
  }
}

