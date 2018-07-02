package ca.mcgill.science.tepid.server.util

import `in`.waffl.q.Q
import ca.mcgill.science.tepid.models.bindings.LOCAL
import ca.mcgill.science.tepid.models.data.Course
import ca.mcgill.science.tepid.models.data.FullUser
import ca.mcgill.science.tepid.models.data.Season
import ca.mcgill.science.tepid.server.generateTestUser
import ca.mcgill.science.tepid.utils.WithLogging
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.mindrot.jbcrypt.BCrypt
import javax.ws.rs.client.Entity
import javax.ws.rs.client.WebTarget
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(Suite::class)
@Suite.SuiteClasses(
        MergeUsersTest::class,
        UpdateDbWithUserTest::class,
        QueryUserDbTest::class,
        AutoSuggestTest::class,
        QueryUserTest::class,
        AuthenticateTest::class
)
class SessionManagerTest

class MergeUsersTest {

    //note that the shortUsers are the same, since they are the unique key
    val testLdapUser = FullUser(displayName = "ldapDN", givenName = "ldapGN", lastName = "ldapLN", shortUser = "SU", longUser = "ldapLU", email = "ldapEM", faculty = "ldapFaculty", groups = listOf("ldapGroups"), courses = listOf(Course("ldapCourseName", Season.FALL, 2222)), studentId = 1111)
    val testDbUser = FullUser(displayName = "dbDN", givenName = "dbGN", lastName = "dbLN", shortUser = "SU", longUser = "dbLU", email = "dbEM", faculty = "dbFaculty", groups = listOf("dbGroups"), courses = listOf(Course("dbCourseName", Season.FALL, 4444)), studentId = 3333, colorPrinting = true, jobExpiration = 12)
    val testMergedUser = testLdapUser.copy(colorPrinting = true, jobExpiration = 12)

    // This should never happen, but it would cause so much trouble downstream that we need to guard against it
    // I don't even have a plausible scenario for how it would happen
    @Test(expected = RuntimeException::class)
    fun testMergeUserNoShortUser () {
        val dbUser:FullUser? = FullUser()
        val ldapUser = FullUser()
        SessionManager.mergeUsers(ldapUser, dbUser)
    }

    @Test
    fun testMergeUsersNonMatchNullDbUser () {
        val dbUser:FullUser? = null
        val ldapUser = testLdapUser
        val actual = SessionManager.mergeUsers(ldapUser, dbUser)
        assertEquals(testLdapUser, actual)
    }
    
    // This should never happen, but it would cause so much trouble downstream that we need to guard against it.
    // It would be indicative that somehow either our database or the LDAP database had become degraded such that whatever was used to query the shortUser of a user (like an email) did not match between our database and the LDAP.
    // For example, if someone had manually changed an email in LDAP to refer to a different shortUser.
    // I see no sane way to proceed automatically in that case
    @Test(expected = RuntimeException::class)
    fun testMergeUsersNonMatchDbUser () {
        val dbUser:FullUser? = testDbUser.copy(shortUser = "dbSU")
        val ldapUser = testLdapUser.copy(shortUser = "ldapSU")
        SessionManager.mergeUsers(ldapUser, dbUser)
    }
    
    @Test
    fun testMergeUsers () {
        val actual = SessionManager.mergeUsers(testLdapUser, testDbUser)
        assertEquals(testMergedUser, actual)
    }

    @Test
    fun testMergeUsersNoStudentIdInLdapUser () {
        val ldapUser = testLdapUser.copy(studentId = -1)
        val actual = SessionManager.mergeUsers(ldapUser, testDbUser)
        val expected = testMergedUser.copy(studentId = testDbUser.studentId)
        assertEquals(expected, actual)
    }

}
class UpdateDbWithUserTest {
    @Before
    fun initTest() {
        objectMockk(CouchDb).mock()
        staticMockk("ca.mcgill.science.tepid.server.util.WebTargetsKt").mock()
        testUser._rev = "1111"
    }

    @After
    fun tearTest() {
        objectMockk(CouchDb).unmock()
        staticMockk("ca.mcgill.science.tepid.server.util.WebTargetsKt").unmock()
    }

    val testSU = "testSU"
    val testUser = FullUser(shortUser = testSU)

    @Test
    fun testUpdateUser () {

        val mockObjectNode = ObjectMapper().createObjectNode()
                .put("ok", true)
                .put("id", "utestSU")
                .put("_rev", "2222")

        val mockResponse = spyk(Response.ok().build())
        every {
            mockResponse.readEntity(ObjectNode::class.java)
        } returns mockObjectNode

        val wt = mockk<WebTarget>()
        every {
            wt.request(MediaType.APPLICATION_JSON).put(Entity.entity(testUser, MediaType.APPLICATION_JSON))
        } returns mockResponse
        every {
            CouchDb.path(ofType(String::class))
        } returns wt

        // Run Test
        SessionManager.updateDbWithUser(testUser)

        // Verifies the path
        verify { CouchDb.path("u" + testSU) }
        verify { wt.request(MediaType.APPLICATION_JSON).put(Entity.entity(testUser, MediaType.APPLICATION_JSON))}
        assertEquals(testUser._rev, "2222")
    }
    
    @Test
    fun testUpdateUserUnsuccessfulResponse () {

        val mockObjectNode = ObjectMapper().createObjectNode()
                .put("ok", true)
                .put("id", "utestSU")
                .put("_rev", "3333")

        val mockResponse = spyk(Response.serverError().build())
        every {
            mockResponse.readEntity(ObjectNode::class.java)
        } returns mockObjectNode

        val wt = mockk<WebTarget>()
        every {
            wt.request(MediaType.APPLICATION_JSON).put(Entity.entity(testUser, MediaType.APPLICATION_JSON))
        } returns mockResponse
        every {
            CouchDb.path(ofType(String::class))
        } returns wt

        // Run Test
        SessionManager.updateDbWithUser(testUser)

        // Verifies the path
        verify { CouchDb.path("u" + testSU) }
        verify { wt.request(MediaType.APPLICATION_JSON).put(Entity.entity(testUser, MediaType.APPLICATION_JSON))}
        assertEquals(testUser._rev, "1111")
    }
    
    @Test
    fun testUpdateUserWithException () {
        val mockResponse = spyk(Response.ok().build())
        every {
            mockResponse.readEntity(ObjectNode::class.java)
        } throws RuntimeException("Testing")

        val wt = mockk<WebTarget>()
        every {
            wt.request(MediaType.APPLICATION_JSON).put(Entity.entity(testUser, MediaType.APPLICATION_JSON))
        } returns mockResponse
        every {
            CouchDb.path(ofType(String::class))
        } returns wt

        // Run Test
        SessionManager.updateDbWithUser(testUser)

        // Verifies the path
        verify { CouchDb.path("u" + testSU) }
        verify { wt.request(MediaType.APPLICATION_JSON).put(Entity.entity(testUser, MediaType.APPLICATION_JSON))}
        // Verifies that it was called, but that it's unchanged
        verify { mockResponse.readEntity(ObjectNode::class.java)}
        assertEquals(testUser._rev, "1111")
    }
}
class QueryUserDbTest {
    lateinit var testUser: FullUser
    lateinit var testOtherUser: FullUser
    lateinit var wt: WebTarget

    @Before
    fun initTest() {
        objectMockk(Config).mock()
        every{Config.ACCOUNT_DOMAIN} returns "config.example.com"
        objectMockk(CouchDb).mock()
        staticMockk("ca.mcgill.science.tepid.server.util.WebTargetsKt").mock()

        wt = mockk<WebTarget>()

        testUser = FullUser(displayName = "dbDN", givenName = "dbGN", lastName = "dbLN", shortUser = "SU", longUser = "db.LU@example.com", email = "db.EM@example.com", faculty = "dbFaculty", groups = listOf("dbGroups"), courses = listOf(Course("dbCourseName", Season.FALL, 4444)), studentId = 3333, colorPrinting = true, jobExpiration = 12)
        testUser._id = "0000"
        testUser._rev = "0001"
        testOtherUser = FullUser(displayName = "ldapDN", givenName = "ldapGN", lastName = "ldapLN", shortUser = "SU", longUser = "ldap.LU@example.com", email = "ldap.EM@example.com", faculty = "ldapFaculty", groups = listOf("ldapGroups"), courses = listOf(Course("ldapCourseName", Season.FALL, 2222)), studentId = 1111)
        testUser._id = "1000"
        testUser._rev = "1001"
    }

    @After
    fun tearTest() {
        objectMockk(CouchDb).unmock()
        staticMockk("ca.mcgill.science.tepid.server.util.WebTargetsKt").unmock()
        objectMockk(Config).unmock()
    }

    private fun makeMocks(userListReturned : List<FullUser>) {
        every {
            CouchDb.path(ofType(CouchDb.CouchDbView::class))
        } returns wt
        every {
            wt.queryParam(ofType(String::class), ofType(String::class))
        } returns wt
        every {
            wt.getViewRows<FullUser>()
        } returns userListReturned
    }

    @Test
    fun testQueryUserDbNullSam () {
        val actual = SessionManager.queryUserDb(null)
        assertEquals(null, actual, "Result was not null")
    }

    @Test
    fun testQueryUserDbByEmail () {
        makeMocks(listOf<FullUser>(testUser, testOtherUser))

        val actual = SessionManager.queryUserDb(testUser.email)

        verify { CouchDb.path(CouchDb.CouchDbView.ByLongUser)}
        verify { wt.queryParam("key", match {it.toString() == "\"db.EM%40config.example.com\""})}
        assertEquals(testUser, actual, "User was not returned when searched by Email")
    }

    @Test
    fun testQueryUserDbByEmailNull () {
        makeMocks(listOf<FullUser>())

        val actual = SessionManager.queryUserDb(testUser.email)

        verify { CouchDb.path(CouchDb.CouchDbView.ByLongUser)}
        verify { wt.queryParam("key", match {it.toString() == "\"db.EM%40config.example.com\""})}
        assertEquals(null, actual, "Null was not returned when nonexistent searched by Email")
    }

    @Test
    fun testQueryUserDbByFullUser () {
        makeMocks(listOf<FullUser>(testUser, testOtherUser))

        val actual = SessionManager.queryUserDb(testUser.longUser)

        verify { CouchDb.path(CouchDb.CouchDbView.ByLongUser)}
        verify { wt.queryParam("key", match {it.toString() == "\"db.LU%40config.example.com\""})}
        assertEquals(testUser, actual, "User was not returned when searched by Email")
    }

    @Test
    fun testQueryUserDbByFullUserNull () {
        makeMocks(listOf<FullUser>())

        val actual = SessionManager.queryUserDb(testUser.longUser)

        verify { CouchDb.path(CouchDb.CouchDbView.ByLongUser)}
        verify { wt.queryParam("key", match {it.toString() == "\"db.LU%40config.example.com\""})}
        assertEquals(null, actual, "Null was not returned when nonexistent searched by Email")
    }

    @Test
    fun testQueryUserDbByStudentId () {
        makeMocks(listOf<FullUser>(testUser, testOtherUser))

        val actual = SessionManager.queryUserDb(testUser.studentId.toString())

        verify { CouchDb.path(CouchDb.CouchDbView.ByStudentId)}
        verify { wt.queryParam("key", match {it.toString() == "3333"})}
        assertEquals(testUser, actual, "User was not returned when searched by studentId")
    }

    @Test
    fun testQueryUserDbByStudentIdNull () {
        makeMocks(listOf<FullUser>())

        val actual = SessionManager.queryUserDb(testUser.studentId.toString())

        verify { CouchDb.path(CouchDb.CouchDbView.ByStudentId)}
        verify { wt.queryParam("key", match {it.toString() == "3333"})}
        assertEquals(null, actual, "Null was not returned when nonexistent searched by studentId")
    }

    @Test
    fun testQueryUserDbByShortUser () {
        every {
            CouchDb.path(ofType(String::class))
        } returns wt
        every{
            wt.getJson(FullUser::class.java)
        } returns testUser
        makeMocks(listOf<FullUser>(testUser, testOtherUser))

        val actual = SessionManager.queryUserDb(testUser.shortUser)

        verify { CouchDb.path("uSU")}
        assertEquals(testUser, actual, "User was not returned when searched by shortUser")
    }

    @Test
    fun testQueryUserDbByShortUserNull () {
        every {
            CouchDb.path(ofType(String::class))
        } returns wt
        every{
            wt.getJson(FullUser::class.java)
        } throws RuntimeException("Testing")
        makeMocks(listOf<FullUser>())

        val actual = SessionManager.queryUserDb(testUser.shortUser)

        verify { CouchDb.path("uSU")}
        assertEquals(null, actual, "Null was not returned when nonexistent searched by shortUser")
    }
}
class AutoSuggestTest {

    lateinit var testUser:FullUser
    lateinit var q:Q<List<FullUser>>
    val testLike = "testLike"
    val testLimit = 15

    @Before
    fun initTest() {
        q = Q.defer<List<FullUser>>()
        testUser = FullUser(displayName = "dbDN", givenName = "dbGN", lastName = "dbLN", shortUser = "SU", longUser = "db.LU@example.com", email = "db.EM@example.com", faculty = "dbFaculty", groups = listOf("dbGroups"), courses = listOf(Course("dbCourseName", Season.FALL, 4444)), studentId = 3333, colorPrinting = true, jobExpiration = 12)
        objectMockk(Config).mock()
        objectMockk(Ldap).mock()


    }
    @After
    fun tearTest() {
        objectMockk(Config).unmock()
        objectMockk(Ldap).unmock()
    }

    @Test
    fun testAutoSuggestLdapEnabled () {
        q.resolve(listOf(testUser))
        val p = q.promise

        every {
            Config.LDAP_ENABLED
        } returns true


        every {
            Ldap.autoSuggest(any(), any())
        } returns q.promise

        val actual = SessionManager.autoSuggest(testLike, testLimit)

        verify{Ldap.autoSuggest(testLike, testLimit)}
        assertEquals(p, actual, "Expected promise not returned")

        objectMockk(Ldap).unmock()

    }

    @Test
    fun testAutoSuggestLdapNotEnabled () {
        q.resolve(emptyList())
        val p = q.promise

        every {
            Config.LDAP_ENABLED
        } returns false

        val actual = SessionManager.autoSuggest(testLike, testLimit)

        verify {Ldap wasNot Called}
        assertEquals(p.result, actual.result, "Expected promise not returned")



    }
}
class QueryUserTest : WithLogging() {

    lateinit var testUser: FullUser

    lateinit var sm : SessionManager

    @Before
    fun initTest() {
        objectMockk(Config).mock()
        objectMockk(Ldap).mock()
        sm = spyk(SessionManager)

        testUser = FullUser(displayName = "dbDN", givenName = "dbGN", lastName = "dbLN", shortUser = "SU", longUser = "db.LU@example.com", email = "db.EM@example.com", faculty = "dbFaculty", groups = listOf("dbGroups"), courses = listOf(Course("dbCourseName", Season.FALL, 4444)), studentId = 3333, colorPrinting = true, jobExpiration = 12)
    }

    @After
    fun tearTest(){
        objectMockk(Config).unmock()
        objectMockk(Ldap).unmock()
    }

    @Test
    fun testQueryUserSamNull () {
        val actual = sm.queryUser(null, null)
        val expected = null

        assertEquals(expected, actual, "Null is not returned when SAM is null")
    }

    @Test
    fun testQueryUserDbHit () {
        every { sm.queryUserDb("SU") } returns testUser

        val actual = sm.queryUser("SU", null)
        val expected = testUser

        assertEquals(expected, actual, "User from DB is not returned when found")
    }

    @Test
    fun testQueryUserDbMissLdapDisabled () {
        every { Config.LDAP_ENABLED } returns false
        every { sm.queryUserDb("SU") } returns null

        val actual = sm.queryUser("SU", null)
        val expected = null

        assertEquals(expected, actual, "Null is not returned when DB miss and Ldap disabled")
    }

    @Test
    fun testQueryUserWithLdapBadSam () {
        every { Config.LDAP_ENABLED } returns true
        every { sm.queryUserDb("db.LU@example.com") } returns null

        val actual = sm.queryUser("db.LU@example.com", null)
        val expected = null

        verify(inverse = true) { sm.updateDbWithUser(any())}
        assertEquals(expected, actual, "SessionManager doesn't return null if SAM is not shortUser")

    }

    @Test
    fun testQueryUserWithLdapLdapUserNull () {
        every { Config.LDAP_ENABLED } returns true
        every { sm.queryUserDb("SU") } returns null
        every { Ldap.queryUserLdap(any(), null) } returns null

        val actual = sm.queryUser("SU", null)
        val expected = null

        verify(inverse = true) { sm.updateDbWithUser(any())}
        assertEquals(expected, actual, "SessionManager doesn't return null if Ldap returns null")
    }


    @Test
    fun testQueryUserWithLdap () {
        every { Config.LDAP_ENABLED } returns true
        every { sm.queryUserDb("SU") } returns null
        every {sm.updateDbWithUser(any())} just runs
        every { Ldap.queryUserLdap(any(), null) } returns testUser


        val actual = sm.queryUser("SU", null)
        val expected = testUser

        verify { sm.updateDbWithUser(testUser) }
        assertEquals(expected, actual, "SessionManager doesn't return null if Ldap returns null")
    }

}
class AuthenticateTest{
    /**
     * These tests do not exhaustively test the authenticate function.
     * Rather, they test the most difficult situations
     * For example, they don't test local auth with LDAP disabled, since this case should be less difficult to handle than with LDAP enabled
     * Basically, if this is something which needs testing, the actual function has logic which breaks the general description
     */

    lateinit var sm : SessionManager
    var testShortUser = "testShortUser"
    var testPassword = "testPassword"
    lateinit var testUser: FullUser
    lateinit var testUserFromDb: FullUser


    @Before
    fun initTest() {
        testUser = generateTestUser("test").copy(shortUser = testShortUser, colorPrinting = true)
        testUserFromDb = generateTestUser("db").copy(
                shortUser = testUser.shortUser,
                studentId = 5555,
                colorPrinting = false
        )
        objectMockk(Config).mock()
        sm = spyk(SessionManager)
        objectMockk(Ldap).mock()
    }
    @After
    fun tearTest(){
        objectMockk(Config).unmock()
        objectMockk(Ldap).unmock()
    }

    @Test
    fun testAuthenticateAuthTypeLocalSuccess () {
        every { Config.LDAP_ENABLED } returns true
        testUser.authType = LOCAL
        testUser.password = BCrypt.hashpw(testPassword, BCrypt.gensalt())
        every{ sm.queryUserDb(testShortUser)} returns testUser

        val actual = sm.authenticate(testShortUser, testPassword)
        val expected = testUser

        assertEquals(expected, actual, "")
    }

    @Test
    fun testAuthenticateAuthTypeLocalFailure () {
        every { Config.LDAP_ENABLED } returns true
        testUser.authType = LOCAL
        testUser.password = BCrypt.hashpw(testPassword, BCrypt.gensalt())
        every{ sm.queryUserDb(testShortUser)} returns testUser

        val actual = sm.authenticate(testShortUser, testPassword+"otherStuff")
        val expected = null

        assertEquals(expected, actual, "")
    }

    @Test
    fun testAuthenticateLdapDisabled () {
        every { Config.LDAP_ENABLED } returns false
        // not necessary, but ensures that there is a password to auth against if it's derping
        testUser.password = BCrypt.hashpw(testPassword, BCrypt.gensalt())

        val actual = sm.authenticate(testShortUser, testPassword)
        val expected = null

        assertEquals(expected, actual, "")
    }

    @Test
    fun testAuthenticateLdapUserNull () {
        every { Config.LDAP_ENABLED } returns true
        every { Ldap.authenticate(testShortUser, testPassword) } returns null

        val actual = sm.authenticate(testShortUser, testPassword)
        val expected = null

        verify { Ldap.authenticate(testShortUser, testPassword) }
        assertEquals(expected, actual, "")
    }

    @Test
    fun testAuthenticateLdap () {
        every { Config.LDAP_ENABLED } returns true
        every { sm.queryUserDb(any()) } returns testUserFromDb
        every { Ldap.authenticate(testShortUser, testPassword) } returns testUser
        every { sm.updateDbWithUser(any())} just runs

        val actual = sm.authenticate(testShortUser, testPassword)
        val expected = SessionManager.mergeUsers(testUser, testUserFromDb)

        verify { Ldap.authenticate(testShortUser, testPassword) }
        verify { sm.mergeUsers(testUser, testUserFromDb) }
        verify { sm.updateDbWithUser(expected) }
        // a check that mergeUsers has merged some DB stuff into ldapUser
        assertFalse ( expected.colorPrinting )
        assertEquals(expected, actual, "")
    }
}
class SetExchangeStudentTest {

    val testSam = "testSam"

    @Before
    fun initTest() {
        objectMockk(Ldap).mock()
        every{Ldap.setExchangeStudent(any(), any())} just runs
        objectMockk(Config).mock()
    }
    @After
    fun tearTest(){
        objectMockk(Ldap).unmock()
        objectMockk(Config).unmock()
    }

    @Test
    fun testSetExchangeStudentLdapEnabled () {
        every { Config.LDAP_ENABLED } returns true
        SessionManager.setExchangeStudent(testSam, true)
        verify{Ldap.setExchangeStudent(testSam, true)}
    }

    @Test
    fun testtestSetExchangeStudentLdapDisabled () {
        every { Config.LDAP_ENABLED } returns false
        SessionManager.setExchangeStudent(testSam, true)
        verify(inverse = true){Ldap.setExchangeStudent(testSam, true)}
    }

}