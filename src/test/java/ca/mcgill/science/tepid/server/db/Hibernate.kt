package ca.mcgill.science.tepid.server.db

import ca.mcgill.science.tepid.models.bindings.TepidDb
import ca.mcgill.science.tepid.models.bindings.TepidDbDelegate
import ca.mcgill.science.tepid.models.bindings.TepidId
import ca.mcgill.science.tepid.models.data.MarqueeData
import junit.framework.Assert.assertNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.*
import javax.persistence.*
import kotlin.test.assertEquals

@Entity
data class TestEntity(
        @Column(nullable = false)
        var content: String = ""
) : @EmbeddedId TepidDb by TepidDbDelegate()

open class DbTest {

    fun <C> persist (obj:C){
        em.transaction.begin()
        em.persist(obj)
        em.transaction.commit()
    }

    fun<T:TepidId> persistMultiple (list:List<T>){
        list.map { e -> e._id=UUID.randomUUID().toString(); persist(e)}
    }

    /*@BeforeEach
    fun initialiseDb(){
       val session = em.unwrap(Session::class.java);
       session.doWork(Work { c: Connection ->
           val script = File(this::class.java.classLoader.getResource("content.sql").file)
           RunScript.execute(c, FileReader(script))
       })
    }*/

    companion object {
        lateinit var emf: EntityManagerFactory
        lateinit var em: EntityManager

        @JvmStatic
        @BeforeAll
        fun initTest() {
            emf = Persistence.createEntityManagerFactory("hibernate-pu-test")
            em = emf.createEntityManager()
        }

        @JvmStatic
        @AfterAll
        fun tearTest(){
            em.clear()
            em.close()
            emf.close()
        }
    }
}

class HibernateCrudTest() : DbTest(){

    @Test
    fun testPsqlCrudCreate(){
        val te = TestEntity("TEST")
        te._id = "ID0"

        pc.create(te)

        val re = em.find(TestEntity::class.java, te._id)
        assertEquals(te, re)
    }

    @Test
    fun testPsqlCrudRead(){
        val te = TestEntity("TEST")
        te._id = "ID1"
        em.transaction.begin()
        em.persist(te)
        em.transaction.commit()

        val re:TestEntity = pc.read(te._id)

        assertEquals(te, re)
    }

    @Test
    fun testPsqlCrudReadAll(){
        val testItems = listOf(TestEntity("1"),TestEntity("2"),TestEntity("3"))
        persistMultiple(testItems)

        val retrieved = pc.readAll(TestEntity::class.java)

        assertEquals(testItems, retrieved)
    }

    @Test
    fun testPsqlCrudUpdate(){
        val te = TestEntity("TEST")
        te._id = "ID2"
        em.transaction.begin()
        em.persist(te)
        em.transaction.commit()
        te.content = "NEW"

        pc.update(te)

        val re = em.find(TestEntity::class.java, te._id)
        assertEquals(te, re)
    }

    @Test
    fun testPsqlCrudDelete(){
        val te = TestEntity("TEST")
        te._id = "ID3"
        em.transaction.begin()
        em.persist(te)
        em.transaction.commit()

        pc.delete(te)

        val re = em.find(TestEntity::class.java, te._id)
        assertNull(re)
    }

    @Test
    fun testPsqlCrudDeleteById(){
        val te = TestEntity("TEST")
        te._id = "ID4"
        em.transaction.begin()
        em.persist(te)
        em.transaction.commit()

        pc.deleteById(TestEntity::class.java, te._id)

        val re = em.find(TestEntity::class.java, te._id)
        assertNull(re)
    }

    companion object {
        lateinit var pc: HibernateCrud

        @JvmStatic
        @BeforeAll
        fun initHelper(){
            pc = HibernateCrud(em)
        }
    }
}

class HibernateMarqueeLayerTest : DbTest(){

    @Test
    fun testMultipleItems(){
        val testItems = listOf(MarqueeData("T1"),MarqueeData("T2"),MarqueeData("T3"))
        persistMultiple(testItems)

        val retrieved = hml.getMarquees()

        assertEquals(testItems, retrieved)


    }

    companion object {
        lateinit var hc: HibernateCrud
        lateinit var hml: HibernateMarqueeLayer

        @JvmStatic
        @BeforeAll
        fun initHelper(){
            hc = HibernateCrud(em)
            hml = HibernateMarqueeLayer(hc)
        }
    }


}