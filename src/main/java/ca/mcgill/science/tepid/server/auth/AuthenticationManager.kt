package ca.mcgill.science.tepid.server.auth

import ca.mcgill.science.tepid.models.bindings.LOCAL
import ca.mcgill.science.tepid.models.bindings.withDbData
import ca.mcgill.science.tepid.models.data.FullUser
import ca.mcgill.science.tepid.server.db.DB
import ca.mcgill.science.tepid.server.db.isSuccessful
import ca.mcgill.science.tepid.server.server.Config
import ca.mcgill.science.tepid.utils.WithLogging
import org.mindrot.jbcrypt.BCrypt
import javax.ws.rs.core.Response

object AuthenticationManager : WithLogging() {

    private val shortUserRegex = Regex("[a-zA-Z]+[0-9]*")

    /**
     * Authenticates user as appropriate:
     * first with local auth (if applicable), then against LDAP (if enabled)
     *
     * @param sam short user
     * @param pw  password
     * @return authenticated user, or null if auth failure
     */
    fun authenticate(sam: String, pw: String): FullUser? {
        val dbUser = queryUserDb(sam)
        log.trace("Db data found for $sam")
        return when {
            dbUser?.authType == LOCAL -> if (BCrypt.checkpw(pw, dbUser.password)) dbUser else null
            Config.LDAP_ENABLED -> {
                var ldapUser = Ldap.authenticate(sam, pw)
                if (ldapUser != null) {
                    ldapUser = mergeUsers(ldapUser, dbUser)
                    updateDbWithUser(ldapUser)
                }
                ldapUser
            }
            else -> null
        }
    }


    /**
     * Retrieve user from DB if available, otherwise retrieves from LDAP
     *
     * @param sam short user
     * @param pw  password
     * @return user if found
     */
    fun queryUser(sam: String?, pw: String?): FullUser? {
        if (sam == null) return null
        log.trace("Querying user: {\"sam\":\"$sam\"}")

        val dbUser = queryUserDb(sam)

        if (dbUser != null) return dbUser

        if (Config.LDAP_ENABLED) {
            if (!sam.matches(shortUserRegex)) return null // cannot query without short user
            val ldapUser = Ldap.queryUser(sam, pw) ?: return null

            updateDbWithUser(ldapUser)

            log.trace("Found user from ldap {\"sam\":\"$sam\", \"longUser\":\"${ldapUser.longUser}\"}")
            return ldapUser
        }
        //finally
        return null
    }

    /**
     * Merge users from LDAP and DB for their corresponding authorities
     * Returns a new users (does not mutate either input
     */
    fun mergeUsers(ldapUser: FullUser, dbUser: FullUser?): FullUser {
        // ensure that short users actually match before attempting any merge
        val ldapShortUser = ldapUser.shortUser
                ?: throw RuntimeException("LDAP user does not have a short user. Maybe this will help {\"ldapUser\":\"$ldapUser,\"dbUser\":\"$dbUser\"}")
        if (dbUser == null) return ldapUser
        if (ldapShortUser != dbUser.shortUser) throw RuntimeException("Attempt to merge to different users {\"ldapUser\":\"$ldapUser,\"dbUser\":\"$dbUser\"}")
        // proceed with data merge
        val newUser = ldapUser.copy()
        newUser.withDbData(dbUser)
        newUser.studentId = if (ldapUser.studentId != -1) ldapUser.studentId else dbUser.studentId
        newUser.preferredName = dbUser.preferredName
        newUser.nick = dbUser.nick
        newUser.colorPrinting = dbUser.colorPrinting
        newUser.jobExpiration = dbUser.jobExpiration
        newUser.updateUserNameInformation()
        return newUser
    }

    /**
     * Uploads a [user] to the DB,
     * with logging for failures
     */
    fun updateDbWithUser(user: FullUser) {
        val shortUser = user.shortUser
                ?: return log.error("Cannot update user, shortUser is null {\"user\": \"$user\"}")
        log.trace("Update db instance {\"user\":\"$shortUser\"}\n")
        try {
            val response: Response = DB.putUser(user)
            if (response.isSuccessful) {
                log.trace("Updated User {\"user\": \"$shortUser\"}")
            } else {
                log.error("Updating DB with user failed: {\"user\": \"$shortUser\",\"response\":\"$response\"}")
            }
        } catch (e: Exception) {
            log.error("Error updating DB with user: {\"user\": \"$shortUser\"}", e)
        }
    }


    /**
     * Retrieve a [FullUser] directly from the database when supplied with either a
     * short user, long user, or student id
     */
    fun queryUserDb(sam: String?): FullUser? {
        sam ?: return null
        val dbUser = DB.getUserOrNull(sam)
        dbUser?._id ?: return null
        log.trace("Found db user {\"sam\":\"$sam\",\"db_id\":\"${dbUser._id}\", \"dislayName\":\"${dbUser.displayName}\"}")
        return dbUser
    }

    fun refreshUser(sam: String): FullUser {
        val dbUser = queryUserDb(sam)
        if (dbUser == null) {
            log.info("Could not fetch user from DB {\"sam\":\"$sam\"}")
            return queryUser(sam, null)
                    ?: throw RuntimeException("Could not fetch user from anywhere {\"sam\":\"$sam\"}")
        }
        if (Config.LDAP_ENABLED) {
            val ldapUser = Ldap.queryUser(sam, null)
                    ?: throw RuntimeException("Could not fetch user from LDAP {\"sam\":\"$sam\"}")
            val refreshedUser = mergeUsers(ldapUser, dbUser)
            if (dbUser.role != refreshedUser.role) {
                SessionManager.invalidateSessions(sam)
            }
            updateDbWithUser(refreshedUser)
            return refreshedUser
        } else {
            return dbUser
        }
    }
}