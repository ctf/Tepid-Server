package ca.mcgill.science.tepid.server.printing

import ca.mcgill.science.tepid.models.bindings.CTFER
import ca.mcgill.science.tepid.models.bindings.ELDER
import ca.mcgill.science.tepid.models.bindings.USER
import ca.mcgill.science.tepid.models.data.FullUser
import ca.mcgill.science.tepid.models.data.Semester
import ca.mcgill.science.tepid.server.auth.LdapConnector
import ca.mcgill.science.tepid.server.db.DB
import ca.mcgill.science.tepid.server.server.Config

object QuotaCounter {

    private val ldapConnector = LdapConnector(timeout = 0)

    fun withCurrentSemester(user: FullUser): FullUser {
        return user.copy(semesters = user.semesters.plus(Semester.current))
    }

    /**
     * Says if the current semester is eligible for granting quota.
     * Checks that the student is in one of the User groups and that they are enrolled in courses.
     */
    fun hasCurrentSemesterEligible(user: FullUser, registeredSemesters: Set<Semester>): Boolean {
        return (setOf<String>(USER, CTFER, ELDER).contains(user.role) && registeredSemesters.contains(Semester.current))
    }

    fun getAllCurrentlyEligible(): Set<FullUser> {
        val filter = "(|${Config.USERS_GROUP.map { "(memberOf:1.2.840.113556.1.4.1941:=cn=${it.name},${Config.GROUPS_LOCATION})" }.joinToString()})"
        return ldapConnector.executeSearch(filter, Long.MAX_VALUE) ?: emptySet()
    }

    fun getAllNeedingGranting(): Set<FullUser> {
        val eligible = getAllCurrentlyEligible().mapNotNull { u -> u._id?.let { Pair(u._id!!, u) } }.toMap()
        val have = DB.getAlreadyGrantedUsers(eligible.keys, Semester.current)

        return eligible.filterKeys { ! have.contains(it) }.values.toSet()
    }
}