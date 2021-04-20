package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Subscribe
import akka.persistence.PersistentRepr
import akka.persistence.query.{EventEnvelope, Sequence}
import akka.serialization.SerializationExtension
import akka.stream.{Attributes, Outlet, SourceShape}
import akka.stream.stage.{AsyncCallback, _}

import scala.concurrent.duration.FiniteDuration

class JdbcEventsByPersistenceIdSource
(
 system:ActorSystem,
  configName:String,
  runtimeData:JdbcJournalRuntimeData,
  live:Boolean,
  refreshInterval: FiniteDuration,
  persistenceId: PersistenceId,
  fromSequenceNr: Long,
  toSequenceNr: Long
) extends GraphStage[SourceShape[EventEnvelope]]{
  val out: Outlet[EventEnvelope] = Outlet("EventEnvelopeSource")
  override val shape: SourceShape[EventEnvelope] = SourceShape(out)


  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogicWithLogging(shape) {


      val serializer = SerializationExtension.get(system).serializerFor(classOf[PersistentRepr])
      private var nextFromSequenceNr = fromSequenceNr
      var buf = Vector.empty[EventEnvelope]
      var pullWithoutData:Boolean = false

      //val continueTask = system.scheduler.schedule( refreshInterval, refreshInterval, self, Continue)

      // Start listening for EntryWrittenToTag-messages which is publish each time an event is written for
      // a specific tag - maybe the one we're tracking
      val pubsubMediator = DistributedPubSub(system).mediator

      var subscribeToTopicsActor:Option[ActorRef] = None

      override def postStop(): Unit = {
        // If we started subscribeToTopicsActor, we should stop it
        subscribeToTopicsActor.foreach( _ ! PoisonPill )
      }

      override def preStart(): Unit = {

        if ( live ) {
          // Since we're a live source,
          // we should trigger on new events.

          // Get the list of all topics to subscribe on
          val subscriptionTopics:List[String] = persistenceId match {
            case p:PersistenceIdSingle =>
              List(EntryWrittenToTag.topic(configName, p.tag))
            case p:PersistenceIdTagsOnly =>
              p.tags.map {
                tag =>
                  EntryWrittenToTag.topic(configName, tag)
              }
          }

          // Get safe callback to use when new entity is written to tag
          val asyncCallback:AsyncCallback[EntryWrittenToTag] = getAsyncCallback( onEntryWrittenToTag )

          // Start actor that can receive subscriptions-notifications and forward notifications
          // to us via callback
          val entryWrittenToTagCallbackActor:ActorRef = system.actorOf(Props( new EntryWrittenToTagCallbackActor(asyncCallback)))
          // Remeber it so that we can stop it later
          subscribeToTopicsActor = Some(entryWrittenToTagCallbackActor)

          // Start subscribing
          subscriptionTopics.foreach {
            subscriptionTopic =>
              pubsubMediator ! Subscribe( subscriptionTopic, entryWrittenToTagCallbackActor)
          }


        }
      }

      def onEntryWrittenToTag(entryWrittenToTag: EntryWrittenToTag): Unit = {
        def doWork(): Unit = {
          log.debug(s"onEntryWrittenToTag pullWithoutData=$pullWithoutData entryWrittenToTag=$entryWrittenToTag")
          if (pullWithoutData) {
            // We do not have anything in the buffer..
            // This can mean that downstream is waiting for data.
            // Therefor we try to load data and push it
            query()
            if (deliverBuf()) {
              // We did deliver data. clear flag
              pullWithoutData = false
            }
          }
        }

        persistenceId match {
          case p:PersistenceIdTagsOnly =>
            // We're tracking a tags
            doWork()

          case p:PersistenceIdSingle =>
            // we're tracking single id
            if( runtimeData.persistenceIdParser.parse(entryWrittenToTag.persistenceId) == persistenceId) {
              // This means one of our events have been written
              doWork()
            }
        }
        if ( persistenceId.isInstanceOf[PersistenceIdSingleTagOnly] ) {
          // We're tracking a tag
          doWork()
        } else if(entryWrittenToTag.persistenceId == persistenceId) {
          // This means one of our events have been written
          doWork()
        }

      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          query()
          if ( deliverBuf() ) {
            pullWithoutData = false
          } else {
            pullWithoutData = true
          }

          log.debug(s"onPull done pullWithoutData=$pullWithoutData")
        }
      })

      def queryAndPush(): Unit = {

      }

      def query(): Unit = {
        if (buf.isEmpty) {
          try {

            log.debug(s"Reading entries for persistenceId=$persistenceId - nextFromSequenceNr=$nextFromSequenceNr, toSequenceNr=$toSequenceNr")
            val entries: List[JournalEntryDto] = runtimeData.repo.loadJournalEntries(persistenceId, nextFromSequenceNr, toSequenceNr, runtimeData.maxRowsPrRead)
            buf = entries.map {
              entry: JournalEntryDto =>

                val persistentRepr = serializer.fromBinary(entry.persistentRepr).asInstanceOf[PersistentRepr]
                nextFromSequenceNr = entry.sequenceNr + 1

                // TODO: Need to add test for this
                val event: AnyRef = persistentRepr.payload match {
                  case q: EventWithInjectableTimestamp => q.cloneWithInjectedTimestamp(entry.timestamp)
                  case x: AnyRef => x
                }

                EventEnvelope(Sequence(entry.sequenceNr), persistentRepr.persistenceId, entry.sequenceNr, event, persistentRepr.timestamp, persistentRepr.metadata)

            }.toVector

            if (!live && buf.isEmpty) {
              log.debug(s"Stopping none-live stream for persistenceId=$persistenceId")
              completeStage()
            }


          } catch {
            case e: Exception =>
              log.error(e, s"JdbcEventsByPersistenceIdActor stopped for persistenceId=$persistenceId")
              failStage(e)
          }
        }
      }

      // Return true if data was pushed
      final def deliverBuf(): Boolean = {
        if (buf.nonEmpty) {
          log.debug(s"deliverBuf buf=$buf")
          emitMultiple(out, buf)
          buf = Vector.empty
          true
        } else {
          false
        }
      }


    }


  class EntryWrittenToTagCallbackActor(asyncCallback:AsyncCallback[EntryWrittenToTag]) extends Actor {
    override def receive: Receive = {
      case  entryWrittenToTag: EntryWrittenToTag =>
        // A new entry is written to tag.
        // We must call callback to trigger stream source
        asyncCallback.invoke(entryWrittenToTag)
    }
  }
}

