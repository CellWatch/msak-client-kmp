package edu.gatech.cc.cellwatch.msak.shared

enum class MsakErrorCode {
    INVALID_URL, DNS, UNAUTHORIZED, HANDSHAKE_FAILED, BAD_JSON, TIMEOUT, CANCELED, UNKNOWN
}

class MsakException(
    val code: MsakErrorCode,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
