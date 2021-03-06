package ca.mcgill.science.tepid.server.rest

import ca.mcgill.science.tepid.models.bindings.ELDER
import ca.mcgill.science.tepid.server.server.JobDataMonitor
import ca.mcgill.science.tepid.server.server.JobMonitor
import ca.mcgill.science.tepid.server.server.SessionMonitor
import ca.mcgill.science.tepid.server.server.UserMembershipMonitor
import ca.mcgill.science.tepid.server.util.getSession
import ca.mcgill.science.tepid.server.util.logMessage
import org.apache.logging.log4j.kotlin.Logging
import javax.annotation.security.RolesAllowed
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.container.ContainerRequestContext
import javax.ws.rs.core.Context
import javax.ws.rs.core.Link
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.UriInfo

@Path("/admin")
class Admin : Logging {
    @Context
    lateinit var uriInfo: UriInfo

    @GET
    @Path("/actions")
    @RolesAllowed(ELDER)
    @Produces(MediaType.APPLICATION_JSON)
    fun getActions(): Set<Link> {
        val builder = uriInfo.absolutePathBuilder
        val actionLinks = setOf(
            Link.fromUri(builder.clone().path("jobmonitor").build()).build(),
            Link.fromUri(builder.clone().path("jobdatamonitor").build()).build(),
            Link.fromUri(builder.clone().path("sessionmonitor").build()).build(),
            Link.fromUri(builder.clone().path("usermembershipmonitor").build()).build()
        )
        return actionLinks
    }

    fun logAction(crc: ContainerRequestContext, jobName: String, action: () -> Unit) {
        val session = crc.getSession()
        logger.info {
            logMessage(
                "beginning manually triggered job",
                "id" to session.user._id,
                "jobName" to jobName,
                "time" to System.currentTimeMillis()
            )
        }
        action()
        logger.info {
            logMessage(
                "completed manually triggered job",
                "id" to session.user._id,
                "jobName" to jobName,
                "time" to System.currentTimeMillis()
            )
        }
    }

    @POST
    @Path("/actions/jobmonitor")
    @RolesAllowed(ELDER)
    fun launchJobMonitor(@Context crc: ContainerRequestContext) {
        logAction(crc, "JobMonitor") { JobMonitor().run() }
    }

    @POST
    @Path("/actions/jobdatamonitor")
    @RolesAllowed(ELDER)
    fun launchJobDataMonitor(@Context crc: ContainerRequestContext) {
        logAction(crc, "JobDataMonitor") { JobDataMonitor().run() }
    }

    @POST
    @Path("/actions/sessionmonitor")
    @RolesAllowed(ELDER)
    fun launchSessionMonitor(@Context crc: ContainerRequestContext) {
        logAction(crc, "SessionMonitor") { SessionMonitor().run() }
    }

    @POST
    @Path("/actions/usermembershipmonitor")
    @RolesAllowed(ELDER)
    fun launchUserMembershipMonitor(@Context crc: ContainerRequestContext) {
        logAction(crc, "UserMembershipMonitor") { UserMembershipMonitor().run() }
    }

    private companion object : Logging
}