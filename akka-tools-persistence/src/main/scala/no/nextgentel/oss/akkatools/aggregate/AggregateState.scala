package no.nextgentel.oss.akkatools.aggregate

trait AggregateState[E, T <: AggregateState[E,T]] {
  def transition(event:E):T

}
