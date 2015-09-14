package no.nextgentel.oss.akkatools.example2.trustaccountcreation

import org.scalatest.{Matchers, FunSuite}

import StateName._

class TACStateTest extends FunSuite with Matchers {

  test("Normal flow") {
    var s = TACState.empty()

    val info = TrustAccountCreationInfo("Customer-1", "type-X")

    s = s.transition( RegisteredEvent(info) )
    s = s.transition( ESigningCompletedEvent() )

    // Make sure we have the correct state..
    assert( TACState(PROCESSING, Some(info), None, None) == s )

    val trustAccountId = "TA-1"
    s = s.transition( CreatedEvent(trustAccountId))
    assert( TACState(CREATED, Some(info), Some(trustAccountId), None) == s )
  }

  test("Completing e-signing after it has failed, should not work") {
    var s = TACState.empty()

    val info = TrustAccountCreationInfo("Customer-1", "type-X")

    s = s.transition( RegisteredEvent(info) )

    // We are failing the e-signing
    s = s.transition( ESigningFailedEvent() )

    val endingState = TACState(DECLINED, Some(info), None, Some("E-Signing failed"))
    assert( endingState == s )

    // No we assume that the e-signing-system is trying to complete the e-signing
    // anyway -- to late.. This should fail.

    val error = intercept[TACError] {
      s = s.transition( ESigningCompletedEvent() )
    }

    // Make sure we got the error we wanted.
    assert( error.getMessage == "Current state is 'Declined'. Got invalid event: ESigningCompletedEvent")

    // Make sure our state has not changed
    assert( endingState == s )


  }



}
