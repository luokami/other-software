package cn.shopledger.nativeapp

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SeedBridge(private val latch: CountDownLatch) { @JavascriptInterface fun done()=latch.countDown() }

@RunWith(AndroidJUnit4::class)
class NativeLedgerTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun clean() { context.deleteDatabase("ledger-native.db");context.deleteDatabase("ledger.db") }
    private fun screenshot(name: String) {
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(context.getExternalFilesDir(null),"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun legacyDatabaseWebPreferencesAndBackupsMigrateTogether(): Unit = runBlocking {
        clean();val date=today()
        val book=Book("旧店铺",listOf(Entry("legacy-entry","expense","蔬菜",1250,date,"青菜"),Entry("deleted-entry","income","张师傅",500,date,deletedAt=Instant.now().toString())),listOf(Loan("legacy-loan","张师傅",2000,date,payments=listOf(Payment("legacy-payment",500,date)))),listOf(Reminder("legacy-reminder","房租","房租",10000,28)),listOf("工具费"))
        val file=context.getDatabasePath("ledger.db");file.parentFile!!.mkdirs()
        val backup=BookCodec.backupJson(Backup(book,DisplayPrefs("dark","large")))
        SQLiteDatabase.openOrCreateDatabase(file,null).use {db->db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY,content TEXT,revision INTEGER,recovery TEXT)");db.execSQL("INSERT INTO books VALUES(1,?,?,?)",arrayOf(BookCodec.bookJson(book).toString(),41,backup))}
        val pin=Privacy.create("123456");val latch=CountDownLatch(1);var web: WebView?=null
        instrumentation.runOnMainSync {
            web=WebView(context).apply {
                settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.blockNetworkLoads=true
                addJavascriptInterface(SeedBridge(latch),"SeedBridge")
                loadDataWithBaseURL("https://appassets.androidplatform.net/","""<script>
                    localStorage.clear();
                    localStorage.setItem('$PIN',${JSONObject.quote(pin)});
                    localStorage.setItem('$THEME','dark');localStorage.setItem('$FONT','large');localStorage.setItem('$AUTO','true');localStorage.setItem('$GUIDE','seen');
                    const d=indexedDB.deleteDatabase('xiaodian-ledger-auto-backups');d.onsuccess=()=>{
                      const r=indexedDB.open('xiaodian-ledger-auto-backups',1);
                      r.onupgradeneeded=()=>r.result.createObjectStore('snapshots',{keyPath:'day'});
                      r.onsuccess=()=>{const db=r.result,tx=db.transaction('snapshots','readwrite');tx.objectStore('snapshots').put({day:'$date',hash:'legacy',backup:$backup});tx.oncomplete=()=>{db.close();SeedBridge.done()}};
                    };
                </script>""","text/html","UTF-8",null)
            }
        }
        assertTrue("Legacy storage seed timed out",latch.await(15,TimeUnit.SECONDS))
        instrumentation.runOnMainSync {web?.destroy()}
        val repository=LedgerRepository(context);repository.initialize()
        assertEquals(book,repository.state.value.book);assertEquals(41L,repository.state.value.revision)
        assertEquals("dark",repository.state.value.display.theme);assertEquals("large",repository.state.value.display.fontSize)
        assertEquals("true",repository.state.value.preferences[AUTO]);assertEquals("seen",repository.state.value.preferences[GUIDE])
        assertTrue(Privacy.verify("123456",repository.state.value.preferences[PIN]!!));assertEquals(1,repository.state.value.backups.size)
        assertEquals(book,BookCodec.parseBackup(repository.state.value.recovery).book)
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use {db->db.rawQuery("SELECT content FROM books WHERE id=1",null).use {c->assertTrue(c.moveToFirst());assertEquals(book,BookCodec.parseBook(c.getString(0)))}}
        repository.initialize {error("Old web settings must never be read twice")}
        assertEquals(book,repository.state.value.book);repository.close();clean()
    }
    @Test fun largeBookAndRecoveryUseSeparateCursorWindows(): Unit = runBlocking {
        clean()
        val book=Book(entries=(1..3000).map { Entry("large-$it","expense","蔬菜",100,today(),note="迁移校验".repeat(22)) })
        val json=BookCodec.bookJson(book).toString()
        val backup=BookCodec.backupJson(Backup(book,DisplayPrefs()))
        assertTrue("Fixture must exceed a combined 2 MiB cursor row",json.toByteArray().size+backup.toByteArray().size>2*1024*1024)
        val file=context.getDatabasePath("ledger.db");file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db->
            db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY,content TEXT,revision INTEGER,recovery TEXT)")
            db.execSQL("INSERT INTO books VALUES(1,?,?,?)",arrayOf(json,75,backup))
        }
        val repository=LedgerRepository(context)
        repository.initialize {LegacySettings()}
        assertTrue(repository.state.value.loaded)
        assertEquals(3000,repository.state.value.book.entries.size)
        assertEquals(3000,BookCodec.parseBackup(repository.state.value.recovery).book.entries.size)
        repository.restore(Backup(Book(),DisplayPrefs()),75)
        assertEquals(3000,BookCodec.parseBackup(repository.state.value.recovery).book.entries.size)
        repository.close();clean()
    }
    @Test fun nativeRecordingLoansPrivacyAndKeyboardFlow() {
        clean()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: LedgerModel
        scenario.onActivity {a->
            assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
            // Synthetic test data only: permit screenshot capture for the CI report.
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            model=ViewModelProvider(a)[LedgerModel::class.java]
        }
        compose.waitUntil(20000) {compose.onAllNodesWithText("稍后再看").fetchSemanticsNodes().isNotEmpty()}
        repeat(3) {compose.onNodeWithText("下一步").performClick()}
        compose.onNodeWithText("开始使用").performClick()
        compose.onNodeWithText("蔬菜").performClick()
        compose.onNodeWithText("具体是什么").performTextInput("青菜")
        compose.onNodeWithText("多少钱（元）").performTextInput("12.50")
        compose.onNodeWithText("记好这一笔").assertIsDisplayed()
        screenshot("native-record-keyboard")
        compose.onNodeWithText("记好这一笔").performClick()
        compose.waitUntil(10000) {model.snapshot.book.activeEntries.size==1}
        compose.onNodeWithText("收支明细").performClick()
        compose.onNodeWithContentDescription("展开当日详情").performClick()
        compose.onNodeWithText("青菜").assertIsDisplayed();compose.onNodeWithText("−12.50").assertIsDisplayed()
        screenshot("native-details")
        compose.onNodeWithText("借款").performClick();compose.onNodeWithText("记借出").performClick()
        compose.onNodeWithText("谁借了钱？").performTextInput("张师傅");compose.onNodeWithText("多少钱（元）").performTextInput("20")
        compose.onNodeWithText("记好这一笔").performClick();compose.waitUntil(10000) {model.snapshot.book.activeLoans.size==1}
        compose.onNodeWithText("记还钱").performClick();compose.onNodeWithText("今天还了几元？").performTextInput("5");compose.onNodeWithText("记好这一笔").performClick()
        compose.waitUntil(10000) {model.snapshot.book.activeLoans.single().remaining==1500L}
        compose.onNodeWithText("¥ 15.00").assertIsDisplayed();screenshot("native-loans")
        assertEquals(1,model.snapshot.book.activeEntries.size)
        compose.onNodeWithText("记账汇总").performClick();compose.onAllNodesWithText("12.50").assertCountEquals(2)
        compose.onNodeWithText("设置").performClick();compose.onNodeWithText("特大").performClick();compose.onNodeWithText("深色").performClick()
        compose.waitUntil(10000) {model.snapshot.display.fontSize=="extra" && model.snapshot.display.theme=="dark"}
        compose.onNodeWithTag("settings-list").performScrollToNode(hasContentDescription("隐私锁"))
        compose.onNodeWithContentDescription("隐私锁").performClick()
        compose.onNodeWithText("6位数字密码").performTextInput("123456");compose.onNodeWithText("再次输入密码").performTextInput("123456");compose.onNodeWithText("开启").performClick()
        compose.waitUntil(10000) {model.pinEnabled && !model.busy}
        compose.onNodeWithText("锁定").performClick();compose.onNodeWithText("小店账本").assertIsDisplayed()
        compose.onNodeWithText("收支明细").performClick();compose.onNodeWithText("6位数字密码").performTextInput("000000");compose.onNodeWithTag("pin-confirm").performClick()
        compose.waitUntil(10000) {model.pinError=="密码不正确"}
        compose.onNodeWithText("6位数字密码").performTextInput("123456");compose.onNodeWithTag("pin-confirm").performClick();compose.waitUntil(10000) {!model.locked && !model.busy}
        compose.onNodeWithText("蔬菜").assertExists()
        val device=UiDevice.getInstance(instrumentation);device.pressHome()
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(10000) {model.locked}
        compose.onNodeWithText("小店账本").assertIsDisplayed()
        screenshot("native-home-locked")
        scenario.close()
    }
}
