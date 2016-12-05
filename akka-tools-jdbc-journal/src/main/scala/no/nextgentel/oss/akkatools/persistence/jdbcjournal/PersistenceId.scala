package no.nextgentel.oss.akkatools.persistence.jdbcjournal

trait PersistenceId

case class PersistenceIdSingle(tag:String, uniqueId:String) extends PersistenceId

trait PersistenceIdTagsOnly {
  def tags:List[String]
}
case class PersistenceIdSingleTagOnly(tag:String) extends PersistenceId with PersistenceIdTagsOnly{
  override def tags: List[String] = List(tag)
}

case class PersistenceIdMultipleTags(tags:List[String]) extends PersistenceId with PersistenceIdTagsOnly


trait PersistenceIdParser {
  def parse(persistenceId:String):PersistenceIdSingle
  def reverse(persistenceId: PersistenceIdSingle):String
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

  override def reverse(persistenceId: PersistenceIdSingle): String = {
    if (includeSplitCharInTag) {
      persistenceId.tag + persistenceId.uniqueId
    } else {
      persistenceId.tag + splitChar + persistenceId.uniqueId
    }
  }
}