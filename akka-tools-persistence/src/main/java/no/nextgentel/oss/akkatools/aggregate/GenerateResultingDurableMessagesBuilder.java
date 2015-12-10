package no.nextgentel.oss.akkatools.aggregate;

import akka.japi.pf.FI;
import akka.japi.pf.Match;
import akka.japi.pf.PFBuilder;

/**
 * Inspired by akka.japi.pf.ReceiveBuilder
 *
 * Used for building a partial function for no.nextgentel.oss.akkatools.aggregate.GeneralAggregate.cmdToEvent().
 *
 * There is both a match on type only, and a match on type and predicate.
 *
 * Inside AbstractGeneralAggregate you can use it like this with Java 8 to define your receive method.
 * <p>
 * Example:
 * </p>
 * <pre>
 * &#64;Override
 * public AbstractGeneralAggregate() {
 *   generateResultingDurableMessages(GenerateResultingDurableMessagesBuilder.
 *       match(IncrementEvent.class, event -> {
 *           // We must send a note somewhere..
 *           JavaState s = (JavaState)state();
 *           String msg = "We are incrementing from " + s.counter;
 *           return ResultingDurableMessages.apply( SendAsDurableMessage.apply(msg, someDest, null));
 *       }).
 *       build()
 *   );
 *
 * }
 * </pre>
 */
public class GenerateResultingDurableMessagesBuilder {
    private GenerateResultingDurableMessagesBuilder() {
    }

    /**
     * Return a new {@link PFBuilder} with a case statement added.
     *
     * @param type  a type to match the argument against
     * @param apply an action to apply to the argument if the type matches
     * @return a builder with the case statement added
     */
    public static <P> PFBuilder<Object, ResultingDurableMessages> match(final Class<? extends P> type, FI.Apply<? extends P, ResultingDurableMessages> apply) {
        return Match.match(type, apply);
    }

    /**
     * Return a new {@link PFBuilder} with a case statement added.
     *
     * @param type      a type to match the argument against
     * @param predicate a predicate that will be evaluated on the argument if the type matches
     * @param apply     an action to apply to the argument if the type matches and the predicate returns true
     * @return a builder with the case statement added
     */
    public static <P> PFBuilder<Object, ResultingDurableMessages> match(final Class<? extends P> type,
                                                                        FI.TypedPredicate<? extends P> predicate,
                                                                        FI.Apply<? extends P, ResultingDurableMessages> apply) {
        return Match.match(type, predicate, apply);
    }

    /**
     * Return a new {@link PFBuilder} with a case statement added.
     *
     * @param object the object to compare equals with
     * @param apply  an action to apply to the argument if the object compares equal
     * @return a builder with the case statement added
     */
    public static <P> PFBuilder<Object, ResultingDurableMessages> matchEquals(P object, FI.Apply<P, ResultingDurableMessages> apply) {
        return Match.matchEquals(object, apply);
    }

    /**
     * Return a new {@link PFBuilder} with a case statement added.
     *
     * @param apply an action to apply to the argument
     * @return a builder with the case statement added
     */
    public static PFBuilder<Object, ResultingDurableMessages> matchAny(FI.Apply<Object, ResultingDurableMessages> apply) {
        return Match.matchAny(apply);
    }


}
