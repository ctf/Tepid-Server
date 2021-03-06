package ca.mcgill.science.tepid.server.db

import ca.mcgill.science.tepid.models.data.FullDestination
import ca.mcgill.science.tepid.models.data.FullSession
import ca.mcgill.science.tepid.models.data.FullUser
import ca.mcgill.science.tepid.models.data.MarqueeData
import ca.mcgill.science.tepid.models.data.PersonalIdentifier
import ca.mcgill.science.tepid.models.data.PrintJob
import ca.mcgill.science.tepid.models.data.PrintQueue
import ca.mcgill.science.tepid.models.data.Semester
import ca.mcgill.science.tepid.server.server.Config
import ca.mcgill.science.tepid.utils.PropsDB
import java.io.InputStream
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.persistence.EntityManagerFactory
import javax.persistence.Persistence

fun makeEntityManagerFactory(persistenceUnitName: String): EntityManagerFactory {
    val props = HashMap<String, String>()
    props.put("javax.persistence.jdbc.url", PropsDB.URL)
    props.put("javax.persistence.jdbc.user", PropsDB.USERNAME)
    props.put("javax.persistence.jdbc.password", PropsDB.PASSWORD)
    val emf = Persistence.createEntityManagerFactory(persistenceUnitName, props)
    return emf
}

fun makeHibernateDb(emf: EntityManagerFactory): DbLayer {
    return DbLayer(
        HibernateDestinationLayer(HibernateCrud(emf, FullDestination::class.java)),
        HibernateJobLayer(HibernateCrud(emf, PrintJob::class.java)),
        HibernateQueueLayer(HibernateCrud(emf, PrintQueue::class.java)),
        HibernateMarqueeLayer(HibernateCrud(emf, MarqueeData::class.java)),
        HibernateSessionLayer(HibernateCrud(emf, FullSession::class.java)),
        HibernateUserLayer(HibernateCrud(emf, FullUser::class.java)),
        HibernateQuotaLayer(emf)
    )
}

class HibernateDestinationLayer(hc: IHibernateCrud<FullDestination, String?>) : DbDestinationLayer,
    IHibernateCrud<FullDestination, String?> by hc

class HibernateJobLayer(hc: IHibernateCrud<PrintJob, String?>) : DbJobLayer, IHibernateCrud<PrintJob, String?> by hc {

    override fun getJobsByQueue(queue: String, maxAge: Long, sortOrder: Order, limit: Int): List<PrintJob> {
        val sort = if (sortOrder == Order.ASCENDING) "ASC" else "DESC"
        val startTime = if (maxAge > 0) Date().time - maxAge else 0
        return dbOp { em ->
            em.createQuery(
                "SELECT c FROM PrintJob c WHERE c.queueId = :queueId AND c.started > :startTime ORDER BY c.started $sort",
                PrintJob::class.java
            ).setParameter("queueId", queue).setParameter("startTime", startTime)
                .setMaxResults(if (limit == -1) Int.MAX_VALUE else limit).resultList
        }
    }

    override fun getJobsByUser(id: Id, sortOrder: Order): List<PrintJob> {
        val sort = if (sortOrder == Order.ASCENDING) "ASC" else "DESC"
        return dbOp { em ->
            em.createQuery(
                "SELECT c FROM PrintJob c WHERE c.userIdentification = :userId ORDER BY c.started $sort",
                PrintJob::class.java
            )
                .setParameter("userId", id)
                .resultList
        }
    }

    override fun getStoredJobs(): List<PrintJob> {
        return dbOp { em ->
            em.createQuery("SELECT c FROM PrintJob c WHERE c.file != null", PrintJob::class.java).resultList
        }
    }

    override fun getJobFile(id: Id, file: String): InputStream? {
        throw NotImplementedError()
    }

    override fun getOldJobs(expireBefore: Long): List<PrintJob> {
        return dbOp { em ->
            em.createQuery("SELECT p FROM PrintJob p WHERE p.printed = -1 AND p.failed = -1", PrintJob::class.java)
                .resultList
        }.filter { maxOf(it.started, it.received, it.processed) < expireBefore }
    }
}

class HibernateQueueLayer(hc: IHibernateCrud<PrintQueue, String?>) : DbQueueLayer,
    IHibernateCrud<PrintQueue, String?> by hc {

    override fun getEta(destinationId: Id): Long {
        return dbOp { em ->
            em.createQuery("SELECT max(c.eta) from PrintJob c WHERE c.destination = :destinationId")
                .setParameter("destinationId", destinationId)
                .singleResult as? Long ?: 0
        }
    }
}

class HibernateMarqueeLayer(hc: IHibernateCrud<MarqueeData, String?>) : DbMarqueeLayer,
    IHibernateCrud<MarqueeData, String?> by hc

class HibernateSessionLayer(hc: IHibernateCrud<FullSession, String?>) : DbSessionLayer,
    IHibernateCrud<FullSession, String?> by hc {
    override fun getSessionIdsForUser(id: Id): List<Id> {
        return dbOp { em ->
            em.createQuery("SELECT c.id FROM FullSession c WHERE c.user._id = :userId", String::class.java)
                .setParameter("userId", id)
                .resultList
        }
    }
}

class HibernateUserLayer(hc: IHibernateCrud<FullUser, String?>) : DbUserLayer, IHibernateCrud<FullUser, String?> by hc {

    override fun putUsers(users: Collection<FullUser>) {
        dbOpTransaction { em ->
            users.forEach { em.merge(it) }
        }
    }

    private val numRegex = Regex("[0-9]+")

    override fun find(sam: PersonalIdentifier): FullUser? {
        try {
            return when {
                sam.contains(".") -> dbOp { em ->
                    em.createQuery("SELECT c FROM FullUser c WHERE c.longUser = :lu", FullUser::class.java)
                        .setParameter("lu", "${sam.substringBefore("@")}@${Config.ACCOUNT_DOMAIN}")
                        .singleResult
                }
                sam.matches(numRegex) -> dbOp { em ->
                    em.createQuery("SELECT c FROM FullUser c WHERE c.studentId = :id", FullUser::class.java)
                        .setParameter("id", sam.toInt())
                        .singleResult
                }
                else -> read(sam)
            }
        } catch (e: Exception) {
            // TODO: More judicious error handling
            return null
        }
    }

    override fun getAllIfPresent(ids: Set<String>): Set<FullUser> {
        return dbOp { em ->
            em.createQuery("SELECT c FROM FullUser c WHERE c._id in :ids", classParameter)
                .setParameter("ids", ids)
                .resultList.toSet()
        }
    }
}

class HibernateQuotaLayer(emf: EntityManagerFactory) : DbQuotaLayer, IHibernateConnector by HibernateConnector(emf) {
    override fun getTotalPrintedCount(id: Id): Int {
        return (dbOp { em ->
            em.createQuery("SELECT SUM(c.pages)+2*SUM(c.colorPages) FROM PrintJob c WHERE c.userIdentification = :userId AND c.isRefunded = FALSE AND c.failed <= 0")
                .setParameter("userId", id).singleResult
        } as? Long ?: 0).toInt()
    }

    /**
     * So this splits the list of users to fetch into batches small enough that Hibernate's AST parser doesn't choke
     * This is because it does a recursive descent of the list of names, which requires _many_ levels and causes a
     * stackoverflow. The folks at Hibernate are aware  of this, and generally think that there queries are silly.
     * Who knows, maybe I'm the fool.
     */
    override fun getAlreadyGrantedUsers(ids: Set<String>, semester: Semester): Set<String> {
        return dbOp { em ->
            {
                val counter: AtomicInteger = AtomicInteger()
                ids.chunked(300).map {
                    em.createQuery(
                        "SELECT c._id FROM FullUser c JOIN c.semesters s WHERE :t in elements(c.semesters) and c._id in :users",
                        String::class.java
                    )
                        .setParameter("t", Semester.current)
                        .setParameter("users", it)
                        .resultList
                }.flatten().toSet()
            }()
        }
    }
}
