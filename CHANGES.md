Version 1.1.1 - 2016-12-19

* Fixing regression-bug in PersistenceIdParserImpl from version 1.0.x to 1.1.0 which used typepath without trailing / by default. PersistenceIdParserImpl.includeSplitCharInTag is now default true 

Version 1.1.0 - 2016-12-16

* Added AggregateStateBase - It is now possible to return new/extra event from inside state-transition
* Simplified jdbc-journal-condifuration
* Breaking source-changes, see [MIGRATION-info.md](MIGRATION-info.md) for info.
* EventsByTagQuery now supports querying for multiple tags in the same stream
* Jdbc-journal can now be configured to use different table-names, enabling the same app to have multiple completely separate journals

Version 1.0.12 - 2016-11-16

* Added error-logging to JdbcEventsByPersistenceIdActor
* Journal now supports injecting timestamp from db into Events extending EventWithInjectableTimestamp
* Built for Scala 2.12

Version 1.0.11 - 2016-09-13

* Upgraded to Akka 2.4.10
* PersistenceQuery's EventsByPersistenceId now also loads new events instantly - No more waiting for the next scheduled read
  * This is achieved using a Distributed pubSub-mechanism
* Exceptions thrown by JacksonJsonSerializer.toBinary when sending msg via akka-remoting no longer [brings the connection down](https://github.com/akka/akka/issues/21343)

Version 1.0.10 - 2016-08-26

* PersistenceQuery's EventsByTag now loads new events instantly - No more waiting for the next scheduled read
  * This is achieved using a Distributed pubSub-mechanism
* Upgraded to Akka 2.4.9

Version 1.0.9.1 - 2016-08-04

* Fixing bug in DMGeneratingVersion - Now saving NewDMGeneratingVersionEvent also when there is nothing to fix

Version 1.0.9 - 2016-08-03

* Added the concept of DMGeneratingVersion
  * If you (by mistake?) change the behaviour of an aggregate in such a way that it now sends a different amount of DMs based on the same events,
   this will (by default) confuse Akka's AtLeastOnceDelivery-mechanism which will try to resend what it believes is old unconfirmed messages.
    This can now be fixed by setting DMGeneratingVersion to a higher int-value. If this is the case, we will see that this is the case,
    prevent these messages from beeing sent, and store the info so we wont have this issue in the future.
    This feature can be used mulitple times (with multiple new versions) over time.
    [See test for more info](https://github.com/NextGenTel/akka-tools/blob/master/akka-tools-persistence/src/test/scala/no/nextgentel/oss/akkatools/aggregate/TestingAggregateNowSendingMoreDMs.scala)

Version 1.0.8 - 2016-06-24

* Fixed bug in ClusterListener-errorDetection - It could wrongly detect error when it saw other alive nodes before it self has joined cluster..
* Fixed NPE bug when reading empty snapshots - Oracle treats empty BLOBs as NULL
* Introduced GeneralAggregateV2 and AggregateStateV2 which let you co-locate all related logic:
  * each different state-implementation has their own cmdToEvent, eventToDMs( old name: generateDMs) and eventToState (old name: transition)
* InternalCommands (e.g GetState) are now logged with debug even though it is configured to log commands as info
* Added skipErrorHandler:Boolean to AggregateError. If true, we skip invoking the custom onError-errorHandler attached to eventResult
* Fixed bug in JdbcSnapshotStore related to reading snapshots when not using serializerId

Version 1.0.7.1 - 2016-05-18

* Upgraded to akka 2.4.5 - including changes need to work with new Java PersistenceQuery-api
* Fixed regression in JdbcJournal - Now we're back to being backward compile-compatible

Version 1.0.7 - 2016-05-13

* Added new config-file param 'jdbcJournalRuntimeDataFactory' which can be used when using multiple journals with different configuration at the same time.
* Introduced GeneralAggregateBase in favor of now deprecated GeneralAggregate
  * GeneralAggregateBase has a simpler more generic approach to generateDM
* Preventing un-needed re-persisting of DurableMessageReceived when we receive the same one multiple times
* Added automatic handling of re-received old commands (via DM)
  * Before: We would process it as a regular command which would fail since we have already processed it and changed our state.
  * Now: We see that we have successfully processed this command before (sender is re-sending it because it has not yet gotten our confirmation), so we ignore it and re-send the confirm
* Improved SeedNodeResolving and Cluster-ErrorDetection
  * **Note!** - This fix requires a **change to the database layout** as described here: [JdbcJournal](https://github.com/NextGenTel/akka-tools/tree/master/akka-tools-jdbc-journal)

Version 1.0.6 - 2016-03-14

* Added canSendAsDurableMessage():Boolean - Use it to check if it is possible to send DM
* Fixed bug in findHighestSequenceNr when using (not full) persisteceIds / wildcards
* Deprecated generateResultingDurableMessages in GeneralAggregate. Instead use new *generateDM* (via state), *generateDMBefore* (via Event), or *generateDMAfter* (via Event)
  * generateDMBefore - gets event as input and is invoked BEFORE event has been applied to state ( same as old generateResultingDurableMessages)
  * generateDMAfter - gets event as input and is invoked AFTER event has been applied to state.
  * generateDM - gets state as input and is invoked AFTER event has been applied
* Fixing [#15](https://github.com/NextGenTel/akka-tools/issues/15) - Snapshot-journal now supports SerializerWithStringManifest
  * **Note!** - This fix requires a **change to the database layout** as described here: [JdbcJournal](https://github.com/NextGenTel/akka-tools/tree/master/akka-tools-jdbc-journal)

Version 1.0.5 - 2016-03-01

* Mitigating akka bug [19893](https://github.com/akka/akka/issues/19893)
* ResultingEvent now uses function that return events instead of just returning the events directly. This allows us to use the onError-impl for both resolving and applying events
  * This change requires you to recompile your code against the new version

Version 1.0.4.1 - 2016-01-06

* Fixing [#14](https://github.com/NextGenTel/akka-tools/issues/14) Exceptions when applying events in EnhancedPersistentActor are now only logged - preventing infinit retrying
* GeneralAggregate.stateInfo() is no longer final - it can now be overridden
* Improved some tests which sometimes failed
* GeneralAggregate's *myDispatcher* is now using the more describing name **dmSelf**. You can now use dmSelf = null to fallback to self() which will make dm.confirm() work as expected in testing.


Version 1.0.4 - 2015-12-15

* It is now optional to implement generateResultingDurableMessages()
* Improving Java 8 support by introducing AbstractGeneralAggregate - Similar to AbstractActor
* Removed some old naming: Using 'persistenceId' instead of old 'processorId' almost everywhere now
* Added **Persistence Query**-support to the [JdbcJournal](https://github.com/NextGenTel/akka-tools/tree/master/akka-tools-jdbc-journal)
* Fixing bug in SeedNodesListOrderingResolver when ourNode is not listed in the seedNode-list and should therefor not be in the ordered seedNode-list
* Fixed bug related to Journal TCK's testcase 'not reset highestSequenceNr after message deletion'
* Upgraded to Akka 2.4.1

Version 1.0.3.2 - 2015-12-01

* Fixing issue with proxying to clusterSingleton created by ClusterSingletonHelper

Version 1.0.3.1 - 2015-11-19

* Fixing regression in 1.0.3 caused by 'successHandler is now executed at the right time' - Now inbound DM cleanup is performed AFTER success-handler has been executed

Version 1.0.3 - 2015-11-18

* Improved default idleTimeout - It is now calculated based on redeliverInterval and warnAfterNumberOfUnconfirmedAttempts  
* Improved bootstrapping of Aggregates using AggregateStarter [commit](https://github.com/NextGenTel/akka-tools/commit/448bd1)
* More fluent api when using ActorWithDMSupport
* Introduced nextState() which holds the soon-to-be state when inside generateResultingDurableMessages()
* Improved ResultingEvents-API
* successHandler is now executed at the right time - after all events are actually persisted

Version 1.0.2 - 2015-10-26

* Fixing [#9](https://github.com/NextGenTel/akka-tools/issues/9) - Restructured how persistenceId's are resolved - Now using persitenceIdBase - Introduces some changes to the GeneralAggregate-API [commit](https://github.com/NextGenTel/akka-tools/commit/ab45697)

Version 1.0.1 - 2015-10-23

* Fixes [#8](https://github.com/NextGenTel/akka-tools/issues/8) Explicit closing sql statements to prevent leakage [commit](https://github.com/NextGenTel/akka-tools/commit/95baa1)

Version 1.0.0 - 2015-10-21

* Changed GeneralAggregateBuilder so that it is possible to initiate views with initialState based on aggregateId [commit](https://github.com/NextGenTel/akka-tools/commit/3b9cfea)
* Added ActorWithDMSupport which makes it easy to implement regular Actors interacting with DMs [commit](https://github.com/NextGenTel/akka-tools/commit/1a8511d)
* Using **Akka 2.4**
* Added another example: Trust Account Creation System [commit](https://github.com/NextGenTel/akka-tools/commit/64671b)
* Changed signature of onCmdToEvent to use AggregateCmd instead of AnyRef [commit](https://github.com/NextGenTel/akka-tools/commit/b0be41b)

Version 0.9.0 - 2015-09-23

* First released open-source version of the original internal NextGenTel project - using Akka 2.3
