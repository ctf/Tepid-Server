package ca.mcgill.science.tepid.server.util

import `in`.waffl.q.Promise
import `in`.waffl.q.Q
import ca.mcgill.science.tepid.ldap.LdapBase
import ca.mcgill.science.tepid.ldap.LdapHelperContract
import ca.mcgill.science.tepid.ldap.LdapHelperDelegate
import ca.mcgill.science.tepid.models.bindings.withDbData
import ca.mcgill.science.tepid.models.data.FullUser
import ca.mcgill.science.tepid.utils.WithLogging
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.*
import javax.naming.NamingException
import javax.naming.directory.*

object Ldap : WithLogging(), LdapHelperContract by LdapHelperDelegate() {

    private val ldap = LdapBase()

    private val numRegex = Regex("[0-9]+")

    private val auth = Config.RESOURCE_USER to Config.RESOURCE_CREDENTIALS

    /**
     * Query extension that will also check from our database
     * [sam] may be the short user, long user, or student id
     */
    fun queryUser(sam: String?, pw: String?): FullUser? {
        if (!Config.LDAP_ENABLED || sam == null) return null
        log.trace("Querying user $sam")

        val termIsId = sam.matches(numRegex)

        if (termIsId) {
            // need to fetch short user from db first
            val dbUser = queryUserDb(sam)
            val shortUser = dbUser?.shortUser ?: return null
            val ldapUser = queryUserLdap(shortUser, pw) ?: return null
            mergeUsers(ldapUser, dbUser, pw != null)
            log.trace("Found user from id $sam: ${ldapUser.shortUser}")
            return ldapUser
        } else {
            // fetch concurrently
            val user = Rx.zipMaybe({ queryUserLdap(sam, pw) }, { queryUserDb(sam) }, { ldapUser, dbUser ->
                ldapUser ?: return@zipMaybe null
                mergeUsers(ldapUser, dbUser, pw != null)
                return@zipMaybe ldapUser
            }).blockingGet()
            log.trace("Found user from $sam: ${user.shortUser}")
            return user
        }
    }

    private fun updateUser(user: FullUser?) {
        user ?: return
        user.salutation = if (user.nick == null)
            if (!user.preferredName.isEmpty()) user.preferredName[user.preferredName.size - 1]
            else user.givenName else user.nick
        if (!user.preferredName.isEmpty())
            user.realName = user.preferredName.asReversed().joinToString(" ")
        else
            user.realName = "${user.givenName} ${user.lastName}"
    }

    /**
     * Update [ldapUser] with db data
     * [queryAsOwner] should be true if [ldapUser] was retrieved by the owner rather than a resource account
     */
    private fun mergeUsers(ldapUser: FullUser, dbUser: FullUser?, queryAsOwner: Boolean) {
        updateUser(ldapUser)
        // ensure that short users actually match before attempting any merge
        val ldapShortUser = ldapUser.shortUser ?: return
        if (ldapShortUser != dbUser?.shortUser) return
        // proceed with data merge
        ldapUser.withDbData(dbUser)
        if (!queryAsOwner) ldapUser.studentId = dbUser.studentId
        ldapUser.preferredName = dbUser.preferredName
        ldapUser.nick = dbUser.nick
        ldapUser.colorPrinting = dbUser.colorPrinting
        ldapUser.jobExpiration = dbUser.jobExpiration

        if (dbUser != ldapUser) {
            log.trace("Update db instance")
            try {
                val response = CouchDb.path("u${ldapUser.shortUser}").putJson(ldapUser)
                if (response.isSuccessful) {
                    val responseObj = response.readEntity(ObjectNode::class.java)
                    val newRev = responseObj.get("_rev")?.asText()
                    if (newRev != null && newRev.length > 3) {
                        ldapUser._rev = newRev
                        log.trace("New rev for ${ldapUser.shortUser}: $newRev")
                    }
                } else {
                    log.error("Response failed: $response")
                }
            } catch (e1: Exception) {
                log.error("Could not put ${ldapUser.shortUser} into db", e1)
            }
        } else {
            log.trace("Not updating dbUser; already matches ldap user")
        }
    }

    /**
     * Retrieve a [FullUser] from ldap
     * [sam] must be a valid short user or long user
     * The resource account will be used as auth if [pw] is null
     */
    private fun queryUserLdap(sam: String, pw: String?): FullUser? {
        val auth = if (pw != null) {
            log.trace("Querying by owner $sam")
            sam to pw
        } else {
            log.trace("Querying by resource")
            Config.RESOURCE_USER to Config.RESOURCE_CREDENTIALS
        }
        return ldap.queryUser(sam, auth)
    }

    /**
     * Retrieve a [FullUser] directly from the database when supplied with either a
     * short user, long user, or student id
     */
    fun queryUserDb(sam: String?): FullUser? {
        sam ?: return null
        val dbUser = when {
            sam.contains(".") -> CouchDb.getViewRows<FullUser>("byLongUser") {
                query("key" to "\"${sam.substringBefore("@")}%40mail.mcgill.ca\"")
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

    @JvmStatic
    fun autoSuggest(like: String, limit: Int): Promise<List<FullUser>> {
        val q = Q.defer<List<FullUser>>()
        if (!Config.LDAP_ENABLED) {
            q.reject("LDAP disabled in source")
            return q.promise
        }
        object : Thread("LDAP AutoSuggest: " + like) {
            override fun run() {
                try {
                    val out = ldap.autoSuggest(like, auth, limit)
                    q.resolve(out)
                } catch (ne: NamingException) {
                    q.reject("Could not get autosuggest", ne)
                }
            }
        }.start()
        return q.promise
    }

    fun authenticate(sam: String, pw: String): FullUser? {
        if (!Config.LDAP_ENABLED) return null
        log.debug("Authenticating $sam against ldap")
        val user = queryUser(sam, pw)
        val shortUser = user?.shortUser
        if (shortUser == null) {
            log.debug("$sam not found")
            return null
        }
        log.trace("Ldap query result for $sam: ${user.longUser}")
        try {
            val auth = ldap.createAuthMap(shortUser, pw)
            InitialDirContext(auth).close()
        } catch (e: Exception) {
            log.warn("Failed to authenticate $sam")
            return null
        }
        return user
    }


    fun setExchangeStudent(sam: String, exchange: Boolean) {
        val longUser = sam.contains(".")
        val ldapSearchBase = ***REMOVED***
        val searchFilter = "(&(objectClass=user)(" + (if (longUser) "userPrincipalName" else "sAMAccountName") + "=" + sam + (if (longUser) "@mail.mcgill.ca" else "") + "))"
        val ctx = ldap.bindLdap(auth) ?: return
        val searchControls = SearchControls()
        searchControls.searchScope = SearchControls.SUBTREE_SCOPE
        var searchResult: SearchResult? = null
        try {
            val results = ctx.search(ldapSearchBase, searchFilter, searchControls)
            searchResult = results.nextElement()
            results.close()
        } catch (e: Exception) {
        }

        if (searchResult == null) return
        val cal = Calendar.getInstance()
        val userDn = searchResult.nameInNamespace
        val year = cal.get(Calendar.YEAR)
        val season = if (cal.get(Calendar.MONTH) < 8) "W" else "F"
        val groupDn = "CN=***REMOVED***$year$season,***REMOVED***,***REMOVED***,OU=***REMOVED***,***REMOVED***,***REMOVED***,***REMOVED***"
        val mods = arrayOfNulls<ModificationItem>(1)
        val mod = BasicAttribute("member", userDn)
        mods[0] = ModificationItem(if (exchange) DirContext.ADD_ATTRIBUTE else DirContext.REMOVE_ATTRIBUTE, mod)
        try {
            ctx.modifyAttributes(groupDn, mods)
            log.info("Added {} to exchange students.", sam)
        } catch (e: NamingException) {
            log.info("Error adding {} to exchange students.", sam)
            e.printStackTrace()
        }

    }


}