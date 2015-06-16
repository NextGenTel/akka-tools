package no.ngt.oss.akkatools.persistence.jdbcjournal

import java.time.OffsetDateTime
import java.util.Date

import no.ngt.oss.akkatools.cluster.ClusterNodeRepo
import org.sql2o.data.{Row, Table}
import org.sql2o.{Sql2oException, Query, Connection, Sql2o}

import scala.concurrent.duration.FiniteDuration

case class JournalEntryDto(processorId: ProcessorId, sequenceNr:Long, persistentRepr:Array[Byte], deleted:Boolean, redeliveries:Int, payloadWriteOnly:String)
case class SnapshotEntry(processorId:String, sequenceNr:Long, timestamp:Long, snapshot:Array[Byte], snapshotClassname:String)


trait StorageRepo {
  def updatePersistenRepr(entry: JournalEntryDto)

  def deleteJournalEntry(processorId: ProcessorId, sequenceNr: Long, permanent: Boolean)

  def deleteJournalEntryTo(processorId: ProcessorId, toSequenceNr: Long, permanent: Boolean)

  def loadJournalEntries(processorId: ProcessorId, fromSequenceNr: Long, toSequenceNr: Long, max: Long): List[JournalEntryDto]

  def findHighestSequenceNr(processorId: ProcessorId, fromSequenceNr: Long): Long

  def writeSnapshot(snapshotEntry: SnapshotEntry)

  def findSnapshotEntry(processorId: String, maxSequenceNr: Long, maxTimestamp: Long): Option[SnapshotEntry]

  def deleteSnapshot(processorId: String, sequenceNr: Long, timestamp: Long)

  def deleteSnapshotsMatching(processorId: String, maxSequenceNr: Long, maxTimestamp: Long)
}

trait JdbcJournalErrorHandler {
  def onError(e:Exception)
}

class JdbcJournalDetectFatalOracleErrorHandler(fatalErrorHandler:JdbcJournalErrorHandler) extends JdbcJournalErrorHandler {
  override def onError(e: Exception): Unit = {
    if (e.getMessage != null && e.getMessage.contains("ORA-00001: unique constraint")) {
      fatalErrorHandler.onError(e)
    }
  }
}

class StorageRepoImpl(sql2o: Sql2o, schemaName: String, errorHandler:JdbcJournalErrorHandler) extends StorageRepo with ClusterNodeRepo {
  import scala.collection.JavaConversions._

  def loadJournalEntries(processorId: ProcessorId, fromSequenceNr: Long, toSequenceNr: Long, max: Long): List[JournalEntryDto] = {
    val sequenceNrColumnName = if (processorId.isFull) {
      "sequenceNr"
    } else {
      "journalIndex"
    }

    val sql = s"select * from (select typePath, id, $sequenceNrColumnName, persistentRepr, deleted, redeliveries from $schemaName.t_journal where typePath = :typePath " +
      (if (processorId.isFull) " and id = :id " else "") +
      s" and $sequenceNrColumnName >= :fromSequenceNr and $sequenceNrColumnName <= :toSequenceNr order by $sequenceNrColumnName) where rownum <= :max"

      // Must use open due to clob/blob
      val conn = sql2o.open
      try {
        val query = conn.createQuery(sql).addParameter("typePath", processorId.typePath)
        if (processorId.isFull) {
          query.addParameter("id", processorId.id)
        }
        query.addParameter("fromSequenceNr", fromSequenceNr).addParameter("toSequenceNr", toSequenceNr).addParameter("max", max)
        val table = query.executeAndFetchTable
        table.rows.toList.map{
          r:Row =>
            JournalEntryDto(
              ProcessorId(r.getString("typePath"), r.getString("id")),
              r.getLong(sequenceNrColumnName),
              r.getObject("persistentRepr", classOf[Array[Byte]]),
              r.getInteger("deleted") > 0,
              r.getInteger("redeliveries"),
              null)
        }
      } finally {
        if (conn != null) conn.close()
      }
  }

  def updatePersistenRepr(entry: JournalEntryDto) {
    if (!entry.processorId.isFull) {
      throw new RuntimeException("Can only write using ProcessorIdType.FULL")
    }
    val sql = s"insert into $schemaName.t_journal (typePath, id, sequenceNr, journalIndex, persistentRepr, deleted, redeliveries, payload_write_only, updated) " +
      s"values (:typePath, :id, :sequenceNr,$schemaName.s_journalIndex_seq.nextval, :persistentRepr, :deleted, :redeliveries, :payload_write_only, sysdate)"

    val update = sql2o.createQuery(sql)
      .addParameter("typePath", entry.processorId.typePath)
      .addParameter("id", entry.processorId.id)
      .addParameter("sequenceNr", entry.sequenceNr)
      .addParameter("persistentRepr", entry.persistentRepr)
      .addParameter("deleted", if (entry.deleted) 1 else 0)
      .addParameter("redeliveries", entry.redeliveries)
      .addParameter("payload_write_only", entry.payloadWriteOnly)

    try {
      update.executeUpdate
    }
    catch {
      case e: Sql2oException => {
        val exception = new Exception("Error updating journal for processorId=" + entry.processorId + " and sequenceNr=" + entry.sequenceNr + ": " + e.getMessage, e)
        errorHandler.onError(e)
        throw exception
      }
    }
  }

  def deleteJournalEntry(processorId: ProcessorId, sequenceNr: Long, permanent: Boolean) {
    val sql = if (permanent) {
      "delete from " + schemaName + ".t_journal where typePath = :typePath and id = :id and sequenceNr = :sequenceNr"
    } else {
      "update " + schemaName + ".t_journal set deleted = 1 where typePath = :typePath and id = :id and sequenceNr = :sequenceNr and deleted = 0"
    }

    sql2o.createQuery(sql).addParameter("typePath", processorId.typePath).addParameter("id", processorId.id).addParameter("sequenceNr", sequenceNr).executeUpdate
  }

  def deleteJournalEntryTo(processorId: ProcessorId, toSequenceNr: Long, permanent: Boolean) {
    val sql = if (permanent) {
      "delete from " + schemaName + ".t_journal where typePath = :typePath and id = :id and sequenceNr <= :toSequenceNr"
    } else {
      "update " + schemaName + ".t_journal set deleted = 1 where typePath = :typePath and id = :id and sequenceNr <= :toSequenceNr and deleted = 0"
    }

    sql2o.createQuery(sql).addParameter("typePath", processorId.typePath).addParameter("id", processorId.id).addParameter("toSequenceNr", toSequenceNr).executeUpdate
  }

  def findHighestSequenceNr(processorId: ProcessorId, fromSequenceNr: Long): Long = {
    val sql = "select max(sequenceNr) from " + schemaName + ".t_journal where typePath = :typePath and id = :id and sequenceNr>=:fromSequenceNr"
    val table = sql2o.createQuery(sql).addParameter("typePath", processorId.typePath).addParameter("id", processorId.id).addParameter("fromSequenceNr", fromSequenceNr).executeAndFetchTable
    if (table.rows.size == 0) {
      return Math.max(fromSequenceNr, 0L)
    }
    val number = Option(table.rows.get(0).getLong(0))
    if (number.isEmpty) {
      return Math.max(fromSequenceNr, 0L)
    }
    return Math.max(fromSequenceNr, number.get)
  }

  def writeSnapshot(e: SnapshotEntry) {
    val sql = "insert into " + schemaName + ".t_snapshot (processorId,sequenceNr,timestamp,snapshot,snapshotClassname,updated) values (:processorId,:sequenceNr,:timestamp,:snapshot,:snapshotClassname,sysdate)"
    try {
      sql2o.createQuery(sql)
        .addParameter("processorId", e.processorId)
        .addParameter("sequenceNr", e.sequenceNr)
        .addParameter("timestamp", e.timestamp)
        .addParameter("snapshot", e.snapshot)
        .addParameter("snapshotClassname", e.snapshotClassname)
        .executeUpdate
    } catch {
      case ex: Sql2oException => {
        errorHandler.onError(ex)
        throw ex
      }
    }
  }

  def findSnapshotEntry(processorId: String, maxSequenceNr: Long, maxTimestamp: Long): Option[SnapshotEntry] = {
    val sql = "select * from (Select * from " + schemaName + ".t_snapshot where processorId = :processorId  and sequenceNr <= :maxSequenceNr  and timestamp <= :maxTimestamp order by timestamp desc) where rownum <= 1"
      // Must use open due to clob/blob
      val conn = sql2o.open
      try {
        val t = conn.createQuery(sql).addParameter("processorId", processorId).addParameter("maxSequenceNr", maxSequenceNr).addParameter("maxTimestamp", maxTimestamp).executeAndFetchTable
        if (t.rows.isEmpty) {
          None
        } else {
          val row: Row = t.rows.get(0)
          val e = SnapshotEntry(
            row.getString("processorId"),
            row.getLong("sequenceNr"),
            row.getLong("timestamp"),
            row.getObject("snapshot", classOf[Array[Byte]]).asInstanceOf[Array[Byte]],
            row.getString("snapshotClassname"))
          Some(e)
        }
      } finally {
        if (conn != null) conn.close()
      }
  }

  def deleteSnapshot(processorId: String, sequenceNr: Long, timestamp: Long) {
    val sql = "delete from " + schemaName + ".t_snapshot where processorId = :processorId  and sequenceNr = :sequenceNr  and timestamp = :timestamp"
    sql2o.createQuery(sql).addParameter("processorId", processorId).addParameter("sequenceNr", sequenceNr).addParameter("timestamp", timestamp).executeUpdate
  }

  def deleteSnapshotsMatching(processorId: String, maxSequenceNr: Long, maxTimestamp: Long) {
    val sql = "delete from " + schemaName + ".t_snapshot where processorId = :processorId  and sequenceNr <= :maxSequenceNr  and timestamp <= :maxTimestamp"
    sql2o.createQuery(sql).addParameter("processorId", processorId).addParameter("maxSequenceNr", maxSequenceNr).addParameter("maxTimestamp", maxTimestamp).executeUpdate
  }

  def writeClusterNodeAlive(nodeName: String, timestamp: OffsetDateTime) {
    var sql = "update " + schemaName + ".t_cluster_nodes set lastSeen = :timestamp where nodeName = :nodeName"
    val updatedRows: Int = sql2o.createQuery(sql).addParameter("nodeName", nodeName).addParameter("timestamp", Date.from(timestamp.toInstant)).executeUpdate.getResult
    if (updatedRows == 0) {
      sql = "insert into " + schemaName + ".t_cluster_nodes(nodeName, lastSeen) values (:nodeName, :timestamp)"
      sql2o.createQuery(sql).addParameter("nodeName", nodeName).addParameter("timestamp", Date.from(timestamp.toInstant)).executeUpdate
    }
  }

  def removeClusterNodeAlive(nodeName: String) {
    val sql: String = "delete from " + schemaName + ".t_cluster_nodes where nodeName = :nodeName"
    sql2o.createQuery(sql).addParameter("nodeName", nodeName).executeUpdate.getResult
  }

  def findAliveClusterNodes(clusterNodesAliveSinceCheck: FiniteDuration): List[String] = {
    val aliveAfter = OffsetDateTime.now.minusSeconds(clusterNodesAliveSinceCheck.toSeconds.toInt)
    val sql = "select nodeName from " + schemaName + ".t_cluster_nodes where lastSeen >= :aliveAfter"
    return sql2o.createQuery(sql).addParameter("aliveAfter", Date.from(aliveAfter.toInstant)).executeScalarList(classOf[String]).toList
  }
}