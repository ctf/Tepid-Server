package ca.mcgill.science.tepid.server.server

import ca.mcgill.science.tepid.server.db.DB
import ca.mcgill.science.tepid.server.util.logAnnounce
import org.apache.logging.log4j.kotlin.Logging
import java.io.File

class JobDataMonitor : Runnable {

    override fun run() {
        logger.trace("deleting expired job data.")
        val now = System.currentTimeMillis()
        try {
            DB.printJobs.getStoredJobs().forEach { j ->

                val id = j._id ?: return@forEach
                DB.printJobs.update(id) {
                    if (deleteDataOn < System.currentTimeMillis()) {

                        val filePath = file
                        if (filePath != null) {
                            try {
                                val f = File(filePath)
                                if (f.exists() && !f.delete())
                                    logger.error(logAnnounce("failed to delete file", "id" to id))
                            } catch (e: Exception) {
                                logger.error(logAnnounce("failed to delete file", "error" to e.message, "id" to id))
                            }
                            file = null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("general failure deleting job data", e)
        }

        logger.info(
            logAnnounce(
                "deleting expired jobs completed",
                "duration" to "${System.currentTimeMillis() - now} ms"
            )
        )
    }

    private companion object : Logging
}
