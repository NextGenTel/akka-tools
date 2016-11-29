package no.nextgentel.oss.akkatools.aggregate;

import scala.Option;

public class JavaState implements AggregateStateJava {

    public static JavaState empty = new JavaState(0);

    public final int counter;

    public JavaState(int counter) {
        this.counter = counter;
    }

    @Override
    public StateTransition<Object, AggregateStateJava> transitionState(Object event) {
        return StateTransition.apply(transition(event), Option.apply(null));
    }

    @Override
    public JavaState transition(Object event) {
        if ( event instanceof IncrementEvent ) {
            return new JavaState(counter + 1);
        } else if ( event instanceof DecrementEvent ) {
            return new JavaState(counter - 1);
        } else {
            throw new JavaError("Invalid command");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        JavaState javaState = (JavaState) o;

        return counter == javaState.counter;

    }

    @Override
    public int hashCode() {
        return counter;
    }

    @Override
    public String toString() {
        return "JavaState{" +
                "counter=" + counter +
                '}';
    }
}
