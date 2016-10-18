package no.nextgentel.oss.akkatools.aggregate

import no.nextgentel.oss.akkatools.serializing.JacksonJsonSerializable

trait AggregateCmd extends JacksonJsonSerializable {
  def id(): String
}
