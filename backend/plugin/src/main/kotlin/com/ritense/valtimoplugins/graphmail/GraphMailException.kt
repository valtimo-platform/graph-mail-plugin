package com.ritense.valtimoplugins.graphmail

open class GraphMailException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int = 500,
) : RuntimeException(message, cause)
