package no.ngt.oss.akkatools.serializing

/**
 * When you have an old type that you do not want your code to use any more,
 * you can use this trait to signalize to NgtJsonSerializer that it can
 * retrieve the new representation of the class / object
 */
trait DepricatedTypeWithMigrationInfo {
  def convertToMigratedType():AnyRef
}
