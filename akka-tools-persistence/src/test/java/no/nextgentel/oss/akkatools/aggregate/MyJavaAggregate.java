package no.nextgentel.oss.akkatools.aggregate;


import akka.actor.ActorPath;
import no.nextgentel.oss.akkatools.persistence.SendAsDurableMessage;

public class MyJavaAggregate extends GeneralAggregateJava {

    private final ActorPath someDest;

    public MyJavaAggregate(ActorPath ourDispatcherActor, ActorPath someDest) {
        super(JavaState.empty, ourDispatcherActor);
        this.someDest = someDest;
    }

    @Override
    public String persistenceIdBase() {
        return "java-";
    }

    @Override
    public ResultingEvent<Object> onCmdToEvent(AggregateCmd cmd) {
        if ( cmd instanceof IncrementCmd ) {
            return ResultingEvent.apply( new IncrementEvent() );
                    //.withSuccessHandler( () -> sender().tell("ok", self()));
        } else if ( cmd instanceof DecrementCmd ) {
            return ResultingEvent.apply( new DecrementEvent() );
        } else {
            throw new JavaError("Unknown cmd");
        }
    }

    @Override
    public ResultingDurableMessages onGenerateResultingDurableMessages(Object event) {
        if ( event instanceof IncrementEvent ) {
            // We must send a note somewhere..
            JavaState s = (JavaState)state();
            String msg = "We are incrementing from " + s.counter;
            return ResultingDurableMessages.apply( SendAsDurableMessage.apply(msg, someDest, null));
        }
        return null;
    }
}
