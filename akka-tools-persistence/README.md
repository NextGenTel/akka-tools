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

![This is how GeneralAggregate works](../img/general-aggregate-flow.jpg "This is how GeneralAggregate works")

The above images shows how GeneralAggregate works.

The red parts indicates what you need to implement.

In short, this is how it works:

When a GeneralAggregate receive a command, we create the event that this command would result in - if it was valid.
Then we test the event by trying to apply it to our state. Since the state is immutable, we can just discard the result.

If the event turns out to be valid, we persist it to the journal.

After the event has been persisted, we apply the event to the state one more time - but this time we keep the result.

After our state has been altered, we check to see of this event shoul make us send some DurableMessages (AtLeastOnceDelivery).

Now we're ready for the next command.

Bu doing it this way, we now have everything we need to automatically create a view (GeneralAggregateView) which understands
all of our events by just using the same state and applying them.
The GeneralAggregateView also keeps a list of all its event, which enables the GetHistory-operation.
This GetHistory, can then be "mounter" on a rest-inteface, making it possible to query out a "beautiful" list of everything that has happened. 


Below is a more detailed explanation of some terms - with links into the example-application: 

Commands
--------------
Commands are sent to a GeneralAggregate from the outside world.

If valid, they may result in an Event/statechange.

They must extend AggregateCmd since we support sharding.

([example-application: Cmds.scala](../examples/aggregates/src/main/scala/no/nextgentel/oss/akkatools/example/booking/Cmds.scala))
([example2-application: Cmds.scala](../examples/aggregates/src/main/scala/no/nextgentel/oss/akkatools/example2/trustaccountcreation/Cmds.scala))

Events
--------------
Events are representing changes to our state and are persisted in the Journal.

They drive the state-machine and may produce *DurableMessages*

([example-application: EventsAndStatemachine.scala](../examples/aggregates/src/main/scala/no/nextgentel/oss/akkatools/example/booking/EventsAndStatemachine.scala))
([example2-application: EventsAndStatemachine.scala](../examples/aggregates/src/main/scala/no/nextgentel/oss/akkatools/example2/trustaccountcreation/EventsAndStatemachine.scala))

DurableMessage
----------------
DurableMessage is a way of using AtLeastOnceDelivery.

When you need to send something with AtLeastOnceDelivery, you send it as a DurableMessage.
The receiver will call confirm() to signal that the message is processed.

When a GeneralAggregate receives an inbound command wrapped inside a DurableMessage, it will automatically confirm it,
if the received Command was processed successfully or if an expected error happened.

Another nice feature is that we can replace the payload of a DurableMessage before sending it
to someone else.

Example usecase:

If a GeneralAggregate needs to get some information, it sends a DurableMessage to a plain actor.
The plain actor gets the information, and replaces the payload of the DurableMessage before sending it back
to the GeneralAggregate.

The Aggregate will then receive the data as a command, and if it is happy with it, it will confirm the DurableMessage.
It is actually confirming its own DurableMessage.

In an error-situation, where the plain actor died, the auto-retrying mechanism would send it again, and this time it might work.

([example-application: Booking.scala which extends GeneralAggregate and sends DurableMessages in method generateResultingDurableMessages()](../examples/aggregates/src/main/scala/no/nextgentel/oss/akkatools/example/booking/Booking.scala))

([example2-application: TAC.scala which extends GeneralAggregate and sends DurableMessages in method generateResultingDurableMessages()](../examples/aggregates/src/main/scala/no/nextgentel/oss/akkatools/example2/trustaccountcreation/TAC.scala))

Expected error
---------------
This is an error of type AggregateError.

An example of an expected error is "We understand what you ask for, but it is an invalid operation given our current state".
In such situations we must confirm the message to stop the auto-retry-mechanism. 

NullPointerException is a typical example of an unexpected error..


The state (machine)
-----------

A GeneralAggregate has its complete state represented in a single

    var state:AggregateState

The AggregateState is immutable and has one method:

    def transition(event:E):AggregateState

This method is used both when testing if incoming commands are valid, and when change our state when events are applied.

It is also used in GeneralAggregateView to make sure we get the same state there.

([example-application: EventsAndStatemachine.scala](../examples/aggregates/src/main/scala/no/nextgentel/oss/akkatools/example/booking/EventsAndStatemachine.scala))
([example2-application: EventsAndStatemachine.scala](../examples/aggregates/src/main/scala/no/nextgentel/oss/akkatools/example2/trustaccountcreation/EventsAndStatemachine.scala))

GeneralAggregate
------------------

GeneralAggregate receives commands and tries to convert them into an event.
The event is then validated by trying to apply it to the state (machine).
If the event is valid, then it is written to the journal.

Before we change the state (machine), we check to see if applying this event should result in
any DurableMessages (Do we need to send an important durable message to some other actor or Aggregate?).

After any DurableMessages has been sent (AtLeastOnceDelivery), we apply the event to the state (machine)
and store the new current state.

([example-application: Booking.scala which extends GeneralAggregate](../examples/aggregates/src/main/scala/no/nextgentel/oss/akkatools/example/booking/Booking.scala))
([example2-application: TAC.scala which extends GeneralAggregate](../examples/aggregates/src/main/scala/no/nextgentel/oss/akkatools/example2/trustaccountcreation/TAC.scala))

Configuration
---------------------

As a convenience, you can include the following config enabling sharding

    include classpath("akka-tools-sharding")



Example
--------------------------

* Examples can be found here: [example-application can be found here](../examples/)