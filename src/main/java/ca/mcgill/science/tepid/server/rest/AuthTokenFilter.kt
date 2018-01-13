package ca.mcgill.science.tepid.server.rest

import ca.mcgill.science.tepid.server.util.getSession
import ca.mcgill.science.tepid.utils.WithLogging
import java.util.*
import javax.annotation.Priority
import javax.ws.rs.Priorities
import javax.ws.rs.container.ContainerRequestContext
import javax.ws.rs.container.ContainerResponseContext
import javax.ws.rs.container.ContainerResponseFilter
import javax.ws.rs.ext.Provider

@Provider
@Priority(Priorities.HEADER_DECORATOR)
class AuthTokenFilter : ContainerResponseFilter {

    init {
        println("Init AuthTokenFilter KT")
    }

    override fun filter(req: ContainerRequestContext, res: ContainerResponseContext) {
        val session = req.getSession(log) ?: return
        res.headers.add(HEADER_SESSION, session.getId())
        res.headers.add(HEADER_ROLE, session.role)
        if (req.headers.containsKey(HEADER_TIMEOUT)) {
            val hours = req.headers.getFirst(HEADER_TIMEOUT).toInt()
            session.expiration = Date(System.currentTimeMillis() + hours * HOUR_IN_MILLIS)
        }

        // our default header is persistent, so we will check against the "false" string rather than the "true" string
        if (req.headers.containsKey(HEADER_PERSISTENT)) {
            session.persistent = !req.headers.getFirst(HEADER_PERSISTENT).equals("false", ignoreCase = true)
        }
    }

    companion object : WithLogging() {

        private const val HOUR_IN_MILLIS = 60 * 60 * 1000
        private const val HEADER_SESSION = "X-TEPID-Session"
        private const val HEADER_ROLE = "X-TEPID-Role"
        private const val HEADER_TIMEOUT = "X-TEPID-Session-Timeout"
        private const val HEADER_PERSISTENT = "X-Tepid-Session-Persistent"

    }

}
