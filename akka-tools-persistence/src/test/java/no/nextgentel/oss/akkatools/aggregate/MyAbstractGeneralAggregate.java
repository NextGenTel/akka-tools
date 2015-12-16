package no.nextgentel.oss.akkatools.aggregate;

import akka.actor.ActorPath;
import no.nextgentel.oss.akkatools.persistence.SendAsDurableMessage;

public class MyAbstractGeneralAggregate extends AbstractGeneralAggregate<JavaState> {

    private final ActorPath someDest;

    public MyAbstractGeneralAggregate(ActorPath dmSelf, ActorPath someDest) {
        super(JavaState.empty, dmSelf);
        this.someDest = someDest;

        cmdToEvent(CmdToEventBuilder.
                match(IncrementCmd.class, cmd -> {
                    return ResultingEventJava.single( new IncrementEvent() )
                            .onSuccess(() -> sender().tell("ok", self()));
                }).
                match(DecrementCmd.class, cmd -> {
                    return ResultingEventJava.single( new DecrementEvent() );
                }).build()

        );

        // It is OPTIONAL to include generateResultingDurableMessages
        generateResultingDurableMessages(GenerateResultingDurableMessagesBuilder.
                match(IncrementEvent.class, event -> {
                    // We must send a note somewhere..
                    JavaState s = (JavaState)state();
                    String msg = "We are incrementing from " + s.counter;
                    return ResultingDurableMessages.apply( SendAsDurableMessage.apply(msg, someDest, null));
                }).
                build()
        );

    }

    @Override
    public String persistenceIdBase() {
        return "abstract-java-";
    }
}
