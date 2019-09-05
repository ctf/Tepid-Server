package ca.mcgill.science.tepid.server.auth

import `in`.waffl.q.Promise
import `in`.waffl.q.Q
import ca.mcgill.science.tepid.models.data.FullUser
import ca.mcgill.science.tepid.server.server.Config
import ca.mcgill.science.tepid.utils.WithLogging
import javax.naming.NamingException
import javax.naming.directory.SearchControls

class AutoSuggest {

    private val ldapConnector = LdapConnector()

    private val auth = Config.RESOURCE_USER to Config.RESOURCE_CREDENTIALS

    fun autoSuggest(like: String, limit: Int): Promise<List<FullUser>> {
        val q = Q.defer<List<FullUser>>()
        object : Thread("LDAP AutoSuggest: " + like) {
            override fun run() {
                val out = queryLdap(like, auth, limit)
                q.resolve(out)
            }
        }.start()
        return q.promise
    }

    fun queryLdap(like: String, auth: Pair<String, String>, limit: Int): List<FullUser> {
        try {
            val ldapSearchBase = Config.LDAP_SEARCH_BASE
            val searchFilter = "(&(objectClass=user)(|(userPrincipalName=$like*)(samaccountname=$like*)))"
            val ctx = ldapConnector.bindLdap(auth) ?: return emptyList()
            val searchControls = SearchControls()
            searchControls.searchScope = SearchControls.SUBTREE_SCOPE
            val results = ctx.search(ldapSearchBase, searchFilter, searchControls)
            val out = mutableListOf<FullUser>()
            var res = 0
            val iter = results.iterator()
            while (iter.hasNext() && res++ < limit) {
                val user = LdapHelper.AttributesToUser(iter.next().attributes, ctx)
                if (user.longUser?.split("@")?.getOrNull(0)?.indexOf(".") ?: -1 > 0)
                    out.add(user)
            }
            //todo update; a crash here will lead to the contents not closing
            results.close()
            ctx.close()
            return out
        } catch (ne: NamingException) {
            log.error("Could not get autosuggest", ne)
            return emptyList()
        }
    }

    private companion object : WithLogging() {

    }
}