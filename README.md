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
| 2.4   | 1.1.3          | [master](https://github.com/NextGenTel/akka-tools)                   |

Changelog
----------------------------

The changes can be found in this [file](CHANGES.md).

Migration from akka-tools 1.0.x
-------------------------------

Info about how to migrate your source from akka-tools 1.0.x to 1.1.x can be found in [MIGRATION-info.md](MIGRATION-info.md).

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

It supports **Persistence Query** with **EventsByTagQuery** (Also multiple tags in the same stream) amongst others


akka-tools-cluster
-------------------------------

[akka-tools-cluster](akka-tools-cluster/README.md) contains cluster-related utilities like:

* dynamic seedNode-resolving
* Split-brain detection and recovering
* ClusterSingletonHelper

examples
-------------------------------

[examples](examples/README.md) contains example code
