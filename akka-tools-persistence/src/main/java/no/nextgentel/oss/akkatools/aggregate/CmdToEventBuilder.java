package no.nextgentel.oss.akkatools.aggregate;

import akka.japi.pf.*;

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
 *   cmdToEvent(CmdToEventBuilder.
 *      match(IncrementCmd.class, cmd -> {
 *          return ResultingEventJava.single( new IncrementEvent() )
 *                      .onSuccess(() -> sender().tell("ok", self()));
 *          }).
 *          match(DecrementCmd.class, cmd -> {
 *               return ResultingEventJava.single( new DecrementEvent() );
 *           }).build()
 *
 *       );
 *   );
 * }
 * </pre>
 */
public class CmdToEventBuilder {
    private CmdToEventBuilder() {
    }

    /**
     * Return a new {@link PFBuilder} with a case statement added.
     *
     * @param type  a type to match the argument against
     * @param apply an action to apply to the argument if the type matches
     * @return a builder with the case statement added
     */
    public static <P> PFBuilder<AggregateCmd, ResultingEventJava> match(final Class<? extends P> type, FI.Apply<? extends P, ResultingEventJava> apply) {
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
    public static <P> PFBuilder<AggregateCmd, ResultingEventJava> match(final Class<? extends P> type,
                                                  FI.TypedPredicate<? extends P> predicate,
                                                  FI.Apply<? extends P, ResultingEventJava> apply) {
        return Match.match(type, predicate, apply);
    }

    /**
     * Return a new {@link PFBuilder} with a case statement added.
     *
     * @param object the object to compare equals with
     * @param apply  an action to apply to the argument if the object compares equal
     * @return a builder with the case statement added
     */
    public static <P> PFBuilder<AggregateCmd, ResultingEventJava> matchEquals(P object, FI.Apply<P, ResultingEventJava> apply) {
        return Match.matchEquals(object, apply);
    }

    /**
     * Return a new {@link PFBuilder} with a case statement added.
     *
     * @param apply an action to apply to the argument
     * @return a builder with the case statement added
     */
    public static PFBuilder<Object, ResultingEventJava> matchAny(FI.Apply<Object, ResultingEventJava> apply) {
        return Match.matchAny(apply);
    }


}
