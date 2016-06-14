package no.nextgentel.oss.akkatools.aggregate

// if skipErrorHandler == true, we'll skip the custom-errorHandler on eventResult (onError)
class AggregateError(errorMsg:String, val skipErrorHandler:Boolean = false) extends RuntimeException(errorMsg)
