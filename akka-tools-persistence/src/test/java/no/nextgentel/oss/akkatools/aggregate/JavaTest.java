package no.nextgentel.oss.akkatools.aggregate;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;
import com.typesafe.config.ConfigFactory;
import no.nextgentel.oss.akkatools.persistence.DurableMessageForwardAndConfirm;
import no.nextgentel.oss.akkatools.testing.AggregateStateGetterJava;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.duration.Duration;

import static org.junit.Assert.assertEquals;

public class JavaTest extends JUnitSuite {

    static Logger logger = LoggerFactory.getLogger(JavaTest.class);
    static ActorSystem system;

    @BeforeClass
    public static void setup() {
        logger.info("Setting up java-test");
        system = ActorSystem.create("JavaTest", ConfigFactory.load("application-test.conf"));
    }

    @AfterClass
    public static void teardown() {
        JavaTestKit.shutdownActorSystem(system);
        system = null;
    }

    @Test
    public void testItFromJava() throws Exception {

        new JavaTestKit(system) {{

            final TestProbe someDest = new TestProbe(system);
            final TestProbe ourDispatcher = new TestProbe(system);
            final TestProbe sender = new TestProbe(system);

            final Props props = Props.create(MyJavaAggregate.class, () -> {
                return new MyJavaAggregate(
                        ourDispatcher.ref().path(),
                        DurableMessageForwardAndConfirm.apply(someDest.ref(), true, system).path());
            });
            final ActorRef main = system.actorOf(props);

            final AggregateStateGetterJava state = new AggregateStateGetterJava(system, main, Duration.create("1s"));


            main.tell("Some-invalid-cmd", null);

            assertEquals(JavaState.empty, state.getState());

            // send cmd
            main.tell( new IncrementCmd("1"), sender.ref());

            // Check state
            assertEquals(new JavaState(1), state.getState());

            // Make sure the successHandler sent back a response
            //sender.expectMsg("ok");

            // assert that durableMessage is sent
            someDest.expectMsg("We are incrementing from 0");

            // send another cmd
            main.tell( new IncrementCmd("1"), null);

            // Check state
            assertEquals(new JavaState(2), state.getState());

            // assert that durableMessage is sent
            someDest.expectMsg("We are incrementing from 1");

            // Decrement it
            main.tell( new DecrementCmd("1"), null);

            // Check state
            assertEquals(new JavaState(1), state.getState());

            // assert that we did not send durableMessage
            someDest.expectNoMsg();

        }};
    }
}
