package ca.mcgill.science.tepid.server.util

import `in`.waffl.q.Promise
import `in`.waffl.q.Q
import ca.mcgill.science.tepid.models.bindings.LOCAL
import ca.mcgill.science.tepid.models.data.FullSession
import ca.mcgill.science.tepid.models.data.FullUser
import ca.mcgill.science.tepid.models.data.User
import ca.mcgill.science.tepid.utils.WithLogging
import org.mindrot.jbcrypt.BCrypt
import java.math.BigInteger
import java.security.SecureRandom

/**
 * SessionManager is responsible for managing sessions and dealing with the underlying authentication.
 * It is analogous to PAM, in that everything which needs authentication or user querying goes through this.
 * For managing sessions, it can start, resume, and end sessions
 * For user querying, it first checks the DB cache. The cache is updated every time a query to the underlying authentication is made.
 * Since it also provides an interface with the underlying authentication, it also provides username autosuggestion and can set users as exchange.
 */

object SessionManager : WithLogging() {

    private const val HOUR_IN_MILLIS = 60 * 60 * 1000
    private val numRegex = Regex("[0-9]+")

    private val random = SecureRandom()

    fun start(user: FullUser, expiration: Int): FullSession {
        val session = FullSession(user = user, expiration = System.currentTimeMillis() + expiration * HOUR_IN_MILLIS)
        val id = BigInteger(130, random).toString(32)
        session._id = id
        log.trace("Creating session $id")
        val out = CouchDb.path(id).putJson(session)
        println(out)
        return session
    }

    operator fun get(token: String): FullSession? {
        val session = CouchDb.path(token).getJsonOrNull<FullSession>() ?: return null
        if (session.isValid()) return session
        log.trace("Session $token is invalid; now ${System.currentTimeMillis()} expiration ${session.expiration}; deleting")
        CouchDb.path(token).deleteRev()
        return null
    }

    /**
     * Check if session exists and isn't expired
     *
     * @param s sessionId
     * @return true for valid, false otherwise
     */
    fun valid(s: String): Boolean = this[s] != null

    fun end(s: String) {
        //todo test
        CouchDb.path(s).deleteRev()
    }

    /**
     * Authenticate user if necessary
     *
     * @param sam short user
     * @param pw  password
     * @return authenticated user
     */
    fun authenticate(sam: String, pw: String): FullUser? {
        val dbUser = Ldap.queryUserDb(sam)
        log.trace("Db data for $sam")
        return if (dbUser?.authType == LOCAL) {
            if (BCrypt.checkpw(pw, dbUser.password)) dbUser else null
        } else {
            Ldap.authenticate(sam, pw)
        }
    }

    /**
     * Retrieve user from Ldap if available, otherwise retrieves from db
     *
     * @param sam short user
     * @param pw  password
     * @return user if found
     * @see [Ldap.queryUserDb]
     */
    fun queryUser(sam: String?, pw: String?): FullUser? =
            if (Config.LDAP_ENABLED) Ldap.queryUser(sam, pw) else Ldap.queryUserDb(sam)

    /**
     * Retrieve a [FullUser] directly from the database when supplied with either a
     * short user, long user, or student id
     */
    private fun queryUserDb(sam: String?): FullUser? {
        sam ?: return null
        val dbUser = when {
            sam.contains(".") -> CouchDb.getViewRows<FullUser>("byLongUser") {
                query("key" to "\"${sam.substringBefore("@")}%40${Config.ACCOUNT_DOMAIN}\"")
            }.firstOrNull()
            sam.matches(numRegex) -> CouchDb.getViewRows<FullUser>("byStudentId") {
                query("key" to sam)
            }.firstOrNull()
            else -> CouchDb.path("u$sam").getJson()
        }
        dbUser?._id ?: return null
        log.trace("Found db user (${dbUser._id}) ${dbUser.displayName} for $sam")
        return dbUser
    }

    /**
     * Sends list of matching [User]s based on current query
     *
     * @param like  prefix
     * @param limit max list size
     * @return list of matching users
     */
    fun autoSuggest(like: String, limit: Int): Promise<List<FullUser>> {
        if (!Config.LDAP_ENABLED) {
            val emptyPromise = Q.defer<List<FullUser>>()
            emptyPromise.resolve(emptyList())
            return emptyPromise.promise
        }
        return Ldap.autoSuggest(like, limit)
    }

    /**
     * Sets exchange student status
     *
     * @param sam      shortId
     * @param exchange boolean for exchange status
     */
    fun setExchangeStudent(sam: String, exchange: Boolean) {
        if (Config.LDAP_ENABLED) Ldap.setExchangeStudent(sam, exchange)
    }

}
