Akka Cluster utilities
==============================

This project contains various utilities useful when using Akka Cluster

seedNode-resolving and error-detection
---------------------------------

By using the following ClusterListener,

    no.nextgentel.oss.akkatools.cluster.ClusterListener
    
it will write alive-messages to the database as long as a cluster node is running.

The [akka-tools-jdbc-journal](../akka-tools-jdbc-journal/)-project supports the needed db-operations.

These alive-messages can be used to

* Detect split-cluster situations
* Resolve seedNode-list


**SeedNodesListOrderingResolver** is used to resolve what should be the "first seedNode" when a node starts up,
by looking at which nodes are currently running.



ClusterSingletonHelper
---------------------

Simplifies using cluster-singletons
