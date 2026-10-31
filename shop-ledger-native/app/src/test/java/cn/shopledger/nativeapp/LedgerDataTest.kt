package cn.shopledger.nativeapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LedgerDataTest {
    private val date="2026-10-31"
    private fun expense(id: String="entry-1",cents: Long=1250)=command("saveEntry",id).put("type","expense").put("category","蔬菜").put("note","青菜").put("cents",cents).put("date",date)
    @Test fun amountAndLoanRules() {
        assertEquals(1250,parseMoney("12.50"))
        listOf("0","-1","1.234","NaN","100000000").forEach { value -> assertTrue(runCatching {parseMoney(value)}.isFailure) }
        var book=BookActions.apply(Book(),expense())
        book=BookActions.apply(book,command("saveLoan","loan-1").put("name","张师傅").put("cents",2000).put("date",date))
        book=BookActions.apply(book,command("repay","payment-1").put("loanId","loan-1").put("cents",500).put("date",date))
        assertEquals(1500,book.loans.single().remaining);assertEquals(1,book.activeEntries.size)
        val same=BookActions.apply(book,command("repay","payment-1").put("loanId","loan-1").put("cents",500).put("date",date))
        assertEquals(book,same)
        assertTrue(runCatching {BookActions.apply(book,command("repay","over").put("loanId","loan-1").put("cents",1501).put("date",date))}.isFailure)
        assertTrue(runCatching {BookActions.apply(book,command("saveLoan","loan-1").put("name","张师傅").put("cents",400).put("date",date))}.isFailure)
    }
    @Test fun backupRoundTripPreservesHiddenFieldsAndDeletedRecords() {
        val book=Book("测试店铺",listOf(Entry("e-1","expense","房租",15000,date,"月租",deletedAt=Instant.now().toString(),reminderRef="r-1:2026-10")),listOf(Loan("l-1","张师傅",1000,date,payments=listOf(Payment("p-1",200,date),Payment("p-2",50,date,Instant.now().toString())))),listOf(Reminder("r-1","房租","房租",15000,31)),listOf("工具费"))
        val backup=Backup(book,DisplayPrefs("dark","large"));val restored=BookCodec.parseBackup(BookCodec.backupJson(backup))
        assertEquals(backup,restored)
        assertEquals(800,restored.book.loans.single().remaining)
        val root=BookCodec.bookJson(book);root.getJSONArray("entries").getJSONObject(0).put("cents",0)
        assertTrue(runCatching {BookCodec.parseBook(root)}.isFailure)
        val duplicate=BookCodec.bookJson(book);duplicate.getJSONArray("entries").put(duplicate.getJSONArray("entries").getJSONObject(0))
        assertTrue(runCatching {BookCodec.parseBook(duplicate)}.isFailure)
        assertTrue(BookCodec.entriesCsv(Book(entries=listOf(Entry("e2","income","=1+1",100,date)))).contains("\"'=1+1\""))
        java.io.File("build/compat-backup.json").apply {parentFile.mkdirs();writeText(BookCodec.backupJson(backup))}
    }
    @Test fun deletionRequiresExplicitRestoreAndKeepsHistory() {
        var book=BookActions.apply(Book(),expense())
        book=BookActions.apply(book,command("deleteEntry","entry-1"));assertEquals(0,book.activeEntries.size)
        book=BookActions.apply(book,command("restoreEntry","entry-1"));assertEquals(1,book.activeEntries.size)
        book=BookActions.apply(book,command("addCategory").put("name","工具费"))
        book=BookActions.apply(book,command("removeCategory").put("name","工具费"));assertEquals(1,book.entries.size)
        assertTrue(runCatching {BookActions.apply(book,command("removeCategory").put("name","蔬菜"))}.isFailure)
    }
    @Test fun legacyPinVerifierMatchesWebCryptoFixture() {
        val pin="123456";val value=Privacy.create(pin)
        assertTrue(Privacy.verify(pin,value));assertFalse(Privacy.verify("000000",value));assertFalse(Privacy.verify("12345",value))
        val legacy="""{"version":1,"salt":"000102030405060708090a0b0c0d0e0f","verifier":"56e0f12982d8214698f473a77b69d38525948dfc7fefe58a8594e0447a4f8eea","iterations":200000}"""
        assertTrue(Privacy.verify("123456",legacy));assertFalse(Privacy.verify("000000",legacy))
        assertTrue(runCatching {Privacy.parse("{}")}.isFailure)
    }
    @Test fun migrationIsAtomicAndDoesNotReimportStaleLegacyDatabase(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("ledger.db")
        val old=context.getDatabasePath("ledger.db");old.parentFile!!.mkdirs()
        val book=BookActions.apply(Book(),expense());val recovery=BookCodec.backupJson(Backup(Book(),DisplayPrefs()))
        SQLiteDatabase.openOrCreateDatabase(old,null).use { db ->
            db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY,content TEXT,revision INTEGER,recovery TEXT)")
            db.execSQL("INSERT INTO books VALUES(1,?,?,?)",arrayOf(BookCodec.bookJson(book).toString(),7,recovery))
        }
        val database=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).allowMainThreadQueries().build()
        val repo=LedgerRepository(context,database)
        val settings=LegacySettings(mapOf(PIN to Privacy.create("123456"),THEME to "dark",FONT to "small",AUTO to "true",GUIDE to "seen"),listOf(Snapshot(date,"old-hash",BookCodec.backupJson(Backup(book,DisplayPrefs("dark","small"))))))
        assertTrue(runCatching {repo.initialize {error("simulated interrupted preference migration")}}.isFailure)
        assertNull(database.ledger().state());assertTrue(old.exists())
        repo.initialize {settings}
        assertEquals(book,repo.state.value.book);assertEquals(7,repo.state.value.revision);assertEquals("dark",repo.state.value.display.theme);assertEquals(1,repo.state.value.backups.size)
        SQLiteDatabase.openDatabase(old.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.execSQL("UPDATE books SET content=?",arrayOf(BookCodec.bookJson(Book(name="stale")).toString()))}
        repo.initialize {error("must not run a second migration")}
        assertEquals(book,repo.state.value.book)
        val before=repo.state.value
        assertTrue(runCatching {repo.restore(Backup(Book(name="wrong"),DisplayPrefs()),before.revision-1)}.isFailure)
        assertEquals(before.book,repo.state.value.book)
        repo.restore(Backup(Book(name="restored"),DisplayPrefs("light","extra")),before.revision)
        assertEquals("restored",repo.state.value.book.name);assertEquals(before.book,BookCodec.parseBackup(repo.state.value.recovery).book)
        assertTrue(repo.state.value.preferences.containsKey(PIN))
        repo.close();context.deleteDatabase("ledger.db")
    }
    @Test fun concurrentWritesAreSerializedAndAutoBackupsKeepSevenDays(): Unit = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("ledger.db")
        val database=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).allowMainThreadQueries().build()
        val repo=LedgerRepository(context,database);repo.initialize()
        (1..12).map { n -> async {repo.mutate(expense("e-$n",n*100L))} }.awaitAll()
        assertEquals(12,repo.state.value.book.entries.size)
        (1..9).forEach {n->database.ledger().putSnapshot(Snapshot("2026-10-${n.toString().padStart(2,'0')}","$n",BookCodec.backupJson(Backup(Book(),DisplayPrefs()))))}
        repo.setPreference(AUTO,"true");assertEquals(7,repo.state.value.backups.size)
        assertEquals(today(),repo.state.value.backups.first().day)
        repo.close()
    }
}
