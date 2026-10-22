package com.opensolr.notes.net

import java.io.IOException

/** The Opensolr session is gone: sign in again. */
class SignInRequiredException : IOException("Opensolr sign-in required")

open class ServiceException(message: String) : IOException(message)

class RateLimitedException(val retryAfterSeconds: Long) : IOException("Rate limited")

class QuotaExceededException(val resetsAt: String?) : IOException("Monthly AI quota reached")

class VectorNotAllowedException : IOException("Vectors are not part of this plan")

class IndexMissingException : IOException("The notes index does not exist")

class IndexLimitException : IOException("No room for another index on this plan")

class EndpointMissingException : java.io.IOException("Endpoint not available")

/** Notes indexes of other phones exist on the account: the reader says whether to re-use one before anything is created. */
class IndexChoiceException(val choices: List<String>) : IOException("Choose an index in Opensolr Notes")
