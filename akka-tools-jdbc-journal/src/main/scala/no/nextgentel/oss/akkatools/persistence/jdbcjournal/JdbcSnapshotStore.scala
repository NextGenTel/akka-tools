package no.nextgentel.oss.akkatools.persistence.jdbcjournal

import akka.actor.ActorLogging
import akka.persistence.{SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria}
import akka.persistence.snapshot.SnapshotStore
import akka.serialization.{SerializationExtension, SerializerWithStringManifest}
import com.typesafe.config.Config

import scala.concurrent.{Future, Promise}
import scala.util.Try

class JdbcSnapshotStore(val config:Config) extends SnapshotStore with ActorLogging with JdbcJournalExtractRuntimeData {

  val repo = runtimeData.repo

  val serialization = SerializationExtension.get(context.system)

  override def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    if (log.isDebugEnabled) {
      log.debug("JdbcSnapshotStore - doLoadAsync: " + persistenceId + " criteria: " + criteria)
    }

    val promise = Promise[Option[SelectedSnapshot]]()
    try {
      repo.findSnapshotEntry(persistenceId, criteria.maxSequenceNr, criteria.maxTimestamp) match {
        case None =>
          if (log.isDebugEnabled) {
            log.debug("JdbcSnapshotStore - doLoadAsync: Not found - " + persistenceId + " criteria: " + criteria)
          }
          promise.success(None)
        case Some(e: SnapshotEntry) =>

          val snapshot = e.serializerId match {
            case Some(serializerId) => serialization.deserialize(e.snapshot, serializerId, e.manifest).get
            case None               => serialization.deserialize(e.snapshot, getClass().getClassLoader().loadClass(e.manifest))
          }

          val selectedSnapshot = SelectedSnapshot(
            new SnapshotMetadata(
              e.persistenceId,
              e.sequenceNr,
              e.timestamp),
            snapshot)

          promise.success(Some(selectedSnapshot))
      }
    } catch {
      case e: Exception =>
        val errorMessage = "Error loading snapshot " + persistenceId + " - " + criteria
        log.error(e, errorMessage)
        promise.failure(new Exception(errorMessage, e));
    }

    promise.future
  }

  override def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    val promise = Promise[Unit]()

    if (log.isDebugEnabled) {
      log.debug("JdbcSnapshotStore - doSaveAsync: " + metadata + " snapshot: " + snapshot)
    }

    try {

      val serializer = serialization.serializerFor(snapshot.getClass)
      val bytes = serializer.toBinary(snapshot.asInstanceOf[AnyRef])
      val manifest:String = serializer match {
        case s:SerializerWithStringManifest => s.manifest(snapshot.asInstanceOf[AnyRef])
        case _                              => snapshot.getClass.getName
      }


      repo.writeSnapshot(
        new SnapshotEntry(
          metadata.persistenceId,
          metadata.sequenceNr,
          metadata.timestamp,
          bytes,
          manifest,
          Some(serializer.identifier)))
      promise.success(Unit)
    } catch {
      case e: Exception => {
        val errorMessage: String = "Error storing snapshot"
        log.error(e, errorMessage)
        promise.failure(new Exception(errorMessage, e))
      }
    }

    promise.future
  }



  override def deleteAsync(metadata: SnapshotMetadata): Future[Unit] = {
    Future.fromTry(Try {
      if (log.isDebugEnabled) {
        log.debug("JdbcSnapshotStore - doDelete: " + metadata)
      }

      repo.deleteSnapshot(metadata.persistenceId, metadata.sequenceNr, metadata.timestamp)
    })
  }

  override def deleteAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Unit] = {
    Future.fromTry(Try {
      if (log.isDebugEnabled) {
        log.debug("JdbcSnapshotStore - doDelete: " + persistenceId + " criteria " + criteria)
      }

      repo.deleteSnapshotsMatching(persistenceId, criteria.maxSequenceNr, criteria.maxTimestamp)
    })
  }
}
