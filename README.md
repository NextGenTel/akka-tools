NextGenTel - Akka Tools
==========================
[![Build Status](https://travis-ci.org/NextGenTel/akka-tools.svg)](https://travis-ci.org/NextGenTel/akka-tools)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/no.nextgentel.oss.akka-tools/akka-tools-parent_2.11/badge.svg)](http://mvnrepository.com/artifact/no.nextgentel.oss.akka-tools/)



This project contains various addons to Akka useful when working with:

* Cluster
* Sharding
* Persistence and Eventsourcing
* Serializing

This code has turned out to be quite useful for NextGenTel and is used in production.

Version-information:
----------------------------

| Akka  | akka-tools     | branch                                                               |
| ----- | -------------- | -------------------------------------------------------------------- |
| 2.4   | 1.0.6          | [master](https://github.com/NextGenTel/akka-tools)                   |
| 2.3.x | 0.9.0          | [akka_2.3](https://github.com/NextGenTel/akka-tools/tree/akka_2.3)   |

Changelog
----------------------------

Version 1.0.7 - Not Released yet

* Added new config-file param 'jdbcJournalRuntimeDataFactory' which can be used when using multiple journals with different configuration at the same time.
* Introduced GeneralAggregateBase in favor of now deprecated GeneralAggregate
  * GeneralAggregateBase has a simpler more generic approach to generateDM
* Preventing un-needed re-persisting of DurableMessageReceived when we receive the same one multiple times

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
 


Below is a summary of the various modules

akka-tools-persistence
-------------------------------

[akka-tools-persistence](akka-tools-persistence/README.md)'s main purpose is to make Akka Persistence more easy to use.

The main components are:

* **GeneralAggregate** - which is built on top of PersistentActor
* **GeneralAggregateView** - which is built on top of PersistentView 


It includes features like:

* Separation of aggregate, view and state(machine)
* both aggregate and view understands the same events and uses the same "state machine"
* integrated cluster/sharding-support
* Simplified AtLeastOnceDelivery-support
* Automatic starting and stopping (of idle) aggregates and views
* Automatic working view that supports getting the current state (ie. to be used from REST) and the full event history (nice when debugging)



akka-tools-json-serializing
-------------------------------

[akka-tools-json-serializing](akka-tools-json-serializing/README.md) is an Akka Serializer implementation that uses Jackson Json.

Json is a good match for evolving data-structures, but this serializer also supports coded data-structure-upgrading

akka-tools-jdbc-journal
-------------------------------

[akka-tools-jdbc-journal](akka-tools-jdbc-journal/README.md) is a JDBC journal-plugin for Akka Persistence.

When used together with **akka-tools-json-serializing**, it also writes the json as 'plain-text' so that a human can understand the written data.

It supports **Persistence Query** with **EventsByTagQuery** amongst others


akka-tools-cluster
-------------------------------

[akka-tools-cluster](akka-tools-cluster/README.md) contains cluster-related utilities like:

* dynamic seedNode-resolving
* Split-brain detection and recovering
* ClusterSingletonHelper

examples
-------------------------------

[examples](examples/README.md) contains example code
