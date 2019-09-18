package ca.mcgill.science.tepid.server.auth

import ca.mcgill.science.tepid.server.server.Config
import ca.mcgill.science.tepid.utils.WithLogging
import java.util.*
import javax.naming.Context
import javax.naming.ldap.InitialLdapContext
import javax.naming.ldap.LdapContext

class LdapConnector {
    fun bindLdap(auth: Pair<String, String>) = bindLdap(auth.first, auth.second)

    /**
     * Create [LdapContext] for given credentials
     */

    fun bindLdap(user: String, password: String): LdapContext? {
        log.trace("Attempting bind to LDAP: {'PROVIDER_URL':'${Config.PROVIDER_URL}', 'SECURITY_PRINCIPAL':'${Config.SECURITY_PRINCIPAL_PREFIX + user}'}")
        try {
            val auth = createAuthMap(user, password)
            return InitialLdapContext(auth, null)
        } catch (e: Exception) {
            log.error("Failed to bind to LDAP {\"user\":\"$user\"}", e)
            return null
        }
    }

    private companion object : WithLogging() {
        /**
         * Defines the environment necessary for [InitialLdapContext]
         */
        private fun createAuthMap(user: String, password: String) = Hashtable<String, String>().apply {
            put(Context.SECURITY_AUTHENTICATION, "simple")
            put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
            put(Context.PROVIDER_URL, Config.PROVIDER_URL)
            put(Context.SECURITY_PRINCIPAL, Config.SECURITY_PRINCIPAL_PREFIX + user)
            put(Context.SECURITY_CREDENTIALS, password)
            put("com.sun.jndi.ldap.read.timeout", "5000")
            put("com.sun.jndi.ldap.connect.timeout", "500")
        }
    }
}