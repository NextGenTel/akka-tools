Akka Tools Persistence
==============================

This project main purpose is to make Akka Persistence more easy to use.

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


The concept
=====================

Commands
--------------
Commands are sent to a GeneralAggregate from the outside world.

If valid, they may result in an Event/statechange.

They must extend AggregateCmd since we support sharding.

Events
--------------
Events are representing changes to our state and are persisted in the Journal.

They drive the state-machine and may produce *External Effects*

DurableMessage
----------------
DurableMessage is a way of using AtLeastOnceDelivery.

When you need to send something with AtLeastOnceDelivery, you send it as a DurableMessage.
The receiver will call confirm() to signal that the message is processed.

When a GeneralAggregate receives a DurableMessage, it will automatically confirm it,
if the received Command was processed successfully or if an expected error happened.

Expected error
---------------
This is an error of type AggregateError.

An example of an expected error is "We understand what you ask for, but it is an invalid operation given our current state".

NullPointerException is a typical example of an unexpected error..

External Effect
--------------
External Effects are DurableMessages to other actors and/or Aggregates produced based on Events that has happend.

The state (machine)
-----------

A GeneralAggregate has its complete state represented in a single

    var state:AggregateState

The AggregateState is immutable and has one method:

    def transition(event:E):AggregateState

This method is used both when testing if incoming events are valid, and when change our state when events are applied.

It is also used in GeneralAggregateView to make sure we get the same state there.


GeneralAggregate
------------------

  