package no.nextgentel.oss.akkatools.example2.trustaccountcreation

import java.util.UUID

import akka.actor.Status.Failure
import akka.actor.ActorSystem
import akka.testkit.{TestProbe, TestKit}
import com.typesafe.config.ConfigFactory
import no.nextgentel.oss.akkatools.example2.other.{DoCreateTrustAccount, DoPerformESigning, DoSendEmailToCustomer}
import no.nextgentel.oss.akkatools.testing.AggregateTesting
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, Matchers, FunSuiteLike}
import org.slf4j.LoggerFactory
import StateName._


class TACAggregateTest (_system:ActorSystem) extends TestKit(_system) with FunSuiteLike with Matchers with BeforeAndAfterAll with BeforeAndAfter {

  def this() = this(ActorSystem("tac-test-actor-system", ConfigFactory.load("application-test.conf")))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  val log = LoggerFactory.getLogger(getClass)
  private def generateId() = UUID.randomUUID().toString


  trait TestEnv extends AggregateTesting[TACState] {
    val id = generateId()
    val eSigningSystem     = TestProbe()
    val emailSystem        = TestProbe()
    val trustAccountSystem = TestProbe()
    val sender             = TestProbe()
    val main = system.actorOf(TACAggregate.props(null, dmForwardAndConfirm(eSigningSystem.ref).path, dmForwardAndConfirm(emailSystem.ref).path, dmForwardAndConfirm(trustAccountSystem.ref).path), id)

    def assertState(correctState:TACState): Unit = {
      assert(correctState == getState())
    }

  }


  test("Normal flow") {

    new TestEnv {

      // Make sure we start with empty state
      assertState(TACState.empty())

      val info = TrustAccountCreationInfo("Customer-1", "type-X")

      // start the trustAccountCreation-process
      sendDMBlocking(main, CreateNewTACCmd(id, info), sender.ref)
      assertState( TACState(PENDING_E_SIGNING, Some(info), None, None) )

      // Make sure the sender (eg. rest endpoint) got ok back
      sender.expectMsg("ok")

      // We must make sure that our e-signing-system has been told that e-signing should start
      eSigningSystem.expectMsg(DoPerformESigning(id, info.customerNo))

      // Fake the completion of the e-signing
      sendDMBlocking(main, ESigningCompletedCmd(id))
      assertState( TACState(PROCESSING, Some(info), None, None) )

      // make sure TrustAccountProcessingSystem is told to process it
      trustAccountSystem.expectMsg(DoCreateTrustAccount(id, info.customerNo, info.trustAccountType))

      // Fake creation of the trustAccount
      val trustAccountId = "TA-1"
      sendDMBlocking(main, CompletedCmd(id, trustAccountId))
      assertState( TACState(CREATED, Some(info), Some(trustAccountId), None) )

      // make sure the customer is emailed
      emailSystem.expectMsg(DoSendEmailToCustomer(info.customerNo, s"Your TrustAccount '$trustAccountId' has been created!"))


    }

  }

  test("declined") {

    new TestEnv {
      // Make sure we start with empty state
      assertState(TACState.empty())

      val info = TrustAccountCreationInfo("Customer-1", "type-X")

      // start the trustAccountCreation-process
      sendDMBlocking(main, CreateNewTACCmd(id, info))
      assertState(TACState(PENDING_E_SIGNING, Some(info), None, None))

      // We must make sure that our e-signing-system has been told that e-signing should start
      eSigningSystem.expectMsg(DoPerformESigning(id, info.customerNo))

      // Fake the completion of the e-signing
      sendDMBlocking(main, ESigningCompletedCmd(id))
      assertState(TACState(PROCESSING, Some(info), None, None))

      // make sure TrustAccountProcessingSystem is told to process it
      trustAccountSystem.expectMsg(DoCreateTrustAccount(id, info.customerNo, info.trustAccountType))

      // Fake decline of the trustAccount
      val cause = "Not a suitable customer"
      sendDMBlocking(main, DeclinedCmd(id, cause))

      // make sure the customer is emailed
      emailSystem.expectMsg(DoSendEmailToCustomer(info.customerNo, s"Sorry.. TAC-failed: $cause"))
    }
  }

  test("Normal flow - with some invalid cmds") {
    new TestEnv {
      // Make sure we start with empty state
      assertState(TACState.empty())

      val info = TrustAccountCreationInfo("Customer-1", "type-X")

      // start the trustAccountCreation-process
      sendDMBlocking(main, CreateNewTACCmd(id, info), sender.ref)
      assertState(TACState(PENDING_E_SIGNING, Some(info), None, None))

      // make sure sender got ok back
      sender.expectMsg("ok")

      // We must make sure that our e-signing-system has been told that e-signing should start
      eSigningSystem.expectMsg(DoPerformESigning(id, info.customerNo))

      // Just to generate an error, we try to create the same TAC again... should fail
      sendDMBlocking(main, CreateNewTACCmd(id, TrustAccountCreationInfo("Another customer", "TX")), sender.ref)
      // Make sure we still have the same state
      assertState(TACState(PENDING_E_SIGNING, Some(info), None, None))

      // Make sure sender got error back this time
      assert(sender.expectMsgAnyClassOf(classOf[Failure]).cause.getMessage == "Failed: Cannot re-create this TAC")

      // Fake the completion of the e-signing
      sendDMBlocking(main, ESigningCompletedCmd(id))
      assertState(TACState(PROCESSING, Some(info), None, None))

      // make sure TrustAccountProcessingSystem is told to process it
      trustAccountSystem.expectMsg(DoCreateTrustAccount(id, info.customerNo, info.trustAccountType))

      // Try to complete the e-signing again - should fail
      sendDMBlocking(main, ESigningCompletedCmd(id))
      // Make sure we have the same state as before
      assertState(TACState(PROCESSING, Some(info), None, None))

      // Fake creation of the trustAccount
      val trustAccountId = "TA-1"
      sendDMBlocking(main, CompletedCmd(id, trustAccountId))
      assertState(TACState(CREATED, Some(info), Some(trustAccountId), None))

      // make sure the customer is emailed
      emailSystem.expectMsg(DoSendEmailToCustomer(info.customerNo, s"Your TrustAccount '$trustAccountId' has been created!"))
    }

  }

}
