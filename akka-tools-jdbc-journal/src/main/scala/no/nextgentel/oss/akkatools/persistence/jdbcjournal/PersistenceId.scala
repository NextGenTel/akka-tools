package no.nextgentel.oss.akkatools.persistence.jdbcjournal

trait PersistenceId {
  def tag:String
  def uniqueId:String
}

case class PersistenceIdSingle(tag:String, uniqueId:String) extends PersistenceId
case class PersistenceIdTagOnly(tag:String) extends PersistenceId {
  override def uniqueId: String = throw new Exception(s"${this} does not have a uniqueId")
}


trait PersistenceIdParser {
  def parse(persistenceId:String):PersistenceIdSingle
}

class PersistenceIdParserImpl(splitChar:Char = '/', includeSplitCharInTag:Boolean = false) extends PersistenceIdParser {
  override def parse(persistenceId: String): PersistenceIdSingle = {
    val i:Int = persistenceId.lastIndexOf(splitChar)
    if (i < 0) throw new Exception(s"Did not find '$splitChar' in persistenceId '$persistenceId'")
    val tag = if (includeSplitCharInTag) {
      persistenceId.substring(0, i + 1)
    } else {
      persistenceId.substring(0, i)
    }
    val uniqueId = persistenceId.substring(i + 1, persistenceId.length)
    PersistenceIdSingle(tag, uniqueId)
  }
}