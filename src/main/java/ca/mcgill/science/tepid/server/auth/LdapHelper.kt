package ca.mcgill.science.tepid.server.auth

import ca.mcgill.science.tepid.models.data.AdGroup
import ca.mcgill.science.tepid.models.data.FullUser
import ca.mcgill.science.tepid.models.data.Season
import ca.mcgill.science.tepid.models.data.Semester
import ca.mcgill.science.tepid.server.printing.QuotaCounter
import ca.mcgill.science.tepid.server.util.logError
import org.apache.logging.log4j.kotlin.Logging
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import javax.naming.NamingException
import javax.naming.directory.Attribute
import javax.naming.directory.Attributes
import javax.naming.ldap.LdapName

class LdapHelper {
    companion object : Logging {
        /**
         * Convert attribute to string list
         */
        fun AttributeToList(attribute: Attribute) = (0 until attribute.size()).map { attribute.get(it).toString() }

        /**
         * Make sure that the regex matches values located in [Semester]
         */
        private val semesterRegex: Regex by lazy { Regex("ou=(fall|winter|summer) (2[0-9]{3})[^0-9]") }

        val shortUserRegex = Regex("[a-zA-Z]+[0-9]*")

        /**
         * Creates a blank user and attempts to retrieve as many attributes
         * as possible from the specified attributes
         */
        fun AttributesToUser(attributes: Attributes): FullUser {
            fun attr(name: String) = attributes.get(name)?.get()?.toString() ?: ""
            var out = FullUser(
                displayName = attr("displayName"),
                givenName = attr("givenName"),
                lastName = attr("sn"),
                longUser = attr("userPrincipalName").toLowerCase(),
                email = attr("mail"),
                middleName = attr("middleName"),
                faculty = attr("department"),
                studentId = attr("employeeID").toIntOrNull() ?: -1
            )
            out._id = attr("sAMAccountName")
            out.updateUserNameInformation()
            try {
                out.activeSince = SimpleDateFormat("yyyyMMddHHmmss.SX").parse(attr("whenCreated")).time
            } catch (e: ParseException) {
            }

            val ldapGroups = parseLdapGroups(attributes)

            out.groups = ldapGroups.filterIsInstance<ParsedLdapGroup.group>().map { g -> g.group }.toSet() // discard semesters
            out.role = AuthenticationFilter.getCtfRole(out)

            out = QuotaCounter.withCurrentSemesterIfEligible(out)

            return out
        }

        private fun getCn(ldapQuery: String): String {
            val dn = LdapName(ldapQuery)
            val cn = dn.get(dn.size() - 1)
            return cn.substringAfter("=")
        }

        internal fun parseLdapGroups(attributes: Attributes): List<ParsedLdapGroup> {
            return AttributeToList(attributes.get("memberOf")).mapNotNull {
                try {
                    val cn = getCn(it)

                    val groupValues = semesterRegex.find(it.toLowerCase(Locale.CANADA))?.groupValues
                    if (groupValues != null) {
                        val semester = Semester(Season(groupValues[1]), groupValues[2].toInt())
                        ParsedLdapGroup.semester(Semester(semester.season, semester.year))
                    } else {
                        ParsedLdapGroup.group(AdGroup(cn))
                    }
                } catch (e: NamingException) {
                    logger.logError(
                            "error instantiating LDAP Groups",
                            e,
                            "user" to (attributes.get("sAMAccountName")?.get()?.toString() ?: ""),
                            "memberOF" to attributes.get("memberOf")
                    )
                    null
                }
            }
        }
    }

    sealed class ParsedLdapGroup {
        data class group(val group: AdGroup) : ParsedLdapGroup()
        data class semester(val semester: Semester) : ParsedLdapGroup()
    }
}