package cn.shopledger.nativeapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import kotlin.coroutines.resume

const val THEME="ledger-theme"
const val FONT="ledger-font-scale"
const val PIN="ledger-privacy-lock"
const val THROTTLE="ledger-lock-throttle"
const val AUTO="ledger-auto-backup-enabled"
const val LAST_BACKUP="ledger-last-backup-at"
const val GUIDE="ledger-first-use-guide-v1"
val LEGACY_KEYS=listOf(THEME,FONT,PIN,THROTTLE,AUTO,LAST_BACKUP,GUIDE)

@Entity(tableName="ledger_state")
data class LedgerState(@PrimaryKey val id: Int=1,val json: String,val revision: Long,val legacyImported: Boolean=true)
@Entity(tableName="recovery_state")
data class RecoveryState(@PrimaryKey val id: Int=1,val json: String)
@Entity(tableName="preferences")
data class Preference(@PrimaryKey val key: String,val value: String)
@Entity(tableName="snapshots")
data class Snapshot(@PrimaryKey val day: String,val hash: String,val json: String)
@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger_state WHERE id=1") fun state(): LedgerState?
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun putState(value: LedgerState)
    @Query("SELECT * FROM recovery_state WHERE id=1") fun recovery(): RecoveryState?
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun putRecovery(value: RecoveryState)
    @Query("SELECT * FROM preferences") fun preferences(): List<Preference>
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun putPreference(value: Preference)
    @Query("DELETE FROM preferences WHERE `key`=:key") fun removePreference(key: String)
    @Query("SELECT * FROM snapshots ORDER BY day DESC") fun snapshots(): List<Snapshot>
    @Insert(onConflict=OnConflictStrategy.REPLACE) fun putSnapshot(value: Snapshot)
    @Query("DELETE FROM snapshots WHERE day=:day") fun removeSnapshot(day: String)
}
@Database(entities=[LedgerState::class,RecoveryState::class,Preference::class,Snapshot::class],version=1,exportSchema=true)
abstract class LedgerDatabase: RoomDatabase() { abstract fun ledger(): LedgerDao }
data class LedgerSnapshot(val book: Book=Book(),val revision: Long=0,val preferences: Map<String,String> = emptyMap(),val backups: List<Snapshot> = emptyList(),val recovery: String="",val loaded: Boolean=false,val failure: String="") {
    val display get() = DisplayPrefs(if(preferences[THEME]=="dark")"dark" else "light",preferences[FONT]?.takeIf { it in FONT_NAMES }?:"standard")
}
data class LegacySettings(val preferences: Map<String,String> = emptyMap(),val backups: List<Snapshot> = emptyList())

/** A one-time compatibility reader. No app screen is rendered by WebView. */
object LegacyPreferences {
    suspend fun read(context: Context): LegacySettings = withContext(Dispatchers.Main) {
        var view: WebView?=null
        try {
            val raw=withTimeout(30000) {
                suspendCancellableCoroutine<String> { continuation ->
                    val bridge=object {
                        @JavascriptInterface fun deliver(value: String) {
                            if(continuation.isActive)continuation.resume(value)
                        }
                    }
                    view=WebView(context).apply {
                        settings.javaScriptEnabled=true;settings.domStorageEnabled=true
                        settings.allowFileAccess=false;settings.allowContentAccess=false;settings.blockNetworkLoads=true
                        addJavascriptInterface(bridge,"MigrationBridge")
                        val script="""
                          <html><head><meta charset="UTF-8"></head><body><script>
                          (function(){
                            const preferences={};
                            ${JSONArray(LEGACY_KEYS)}.forEach(k=>{const v=localStorage.getItem(k);if(v!==null)preferences[k]=v});
                            const done=backups=>MigrationBridge.deliver(JSON.stringify({preferences,backups}));
                            const fail=()=>MigrationBridge.deliver(JSON.stringify({error:'旧自动备份无法读取，请重试'}));
                            const request=indexedDB.open('xiaodian-ledger-auto-backups');
                            request.onerror=fail;request.onblocked=fail;
                            request.onsuccess=()=>{
                              const db=request.result;
                              if(!db.objectStoreNames.contains('snapshots')){db.close();done([]);return;}
                              const tx=db.transaction('snapshots','readonly'),read=tx.objectStore('snapshots').getAll();
                              tx.oncomplete=()=>{const values=read.result;db.close();done(values)};
                              tx.onabort=()=>{db.close();fail()};
                            };
                          })();</script></body></html>
                        """.trimIndent()
                        loadDataWithBaseURL("https://appassets.androidplatform.net/",script,"text/html","UTF-8",null)
                    }
                }
            }
            require(raw.toByteArray().size<=16_000_000) { "旧自动备份超过迁移容量" }
            val o=JSONObject(raw);require(!o.has("error")) { o.optString("error") }
            val p=o.getJSONObject("preferences");val prefs=LEGACY_KEYS.filter { p.has(it) }.associateWith { p.getString(it) }
            prefs[PIN]?.let { Privacy.parse(it) }
            val a=o.getJSONArray("backups")
            val snapshots=(0 until a.length()).map { i ->
                val record=a.getJSONObject(i);val day=BookCodec.date(record.get("day"));val content=record.getJSONObject("backup").toString()
                val backup=BookCodec.parseBackup(content)
                Snapshot(day,hash(backup),BookCodec.backupJson(backup))
            }.sortedByDescending { it.day }.take(7)
            LegacySettings(prefs,snapshots)
        } finally { view?.removeJavascriptInterface("MigrationBridge");view?.stopLoading();view?.destroy() }
    }
}

fun hash(backup: Backup): String = MessageDigest.getInstance("SHA-256").digest((BookCodec.bookJson(backup.book).toString()+backup.preferences.theme+backup.preferences.fontSize).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

class LedgerRepository(private val context: Context,val database: LedgerDatabase=Room.databaseBuilder(context,LedgerDatabase::class.java,"ledger-native.db").build()) {
    private val mutex=Mutex()
    private val mutable=MutableStateFlow(LedgerSnapshot())
    val state=mutable.asStateFlow()
    private val dao get()=database.ledger()

    suspend fun initialize(reader: suspend ()->LegacySettings={LegacyPreferences.read(context)}) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if(dao.state()==null) {
                    val old=context.getDatabasePath("ledger.db")
                    var book=Book();var revision=0L;var recovery=""
                    if(old.exists()) {
                        SQLiteDatabase.openDatabase(old.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                            db.rawQuery("SELECT content,revision FROM books WHERE id=1",null).use { cursor ->
                                if(cursor.moveToFirst()) { book=BookCodec.parseBook(cursor.getString(0));revision=cursor.getLong(1) }
                            }
                            // Each legacy value fits one CursorWindow; both together may exceed it.
                            db.rawQuery("SELECT recovery FROM books WHERE id=1",null).use { cursor ->if(cursor.moveToFirst())recovery=cursor.getString(0).orEmpty() }
                            if(recovery.isNotEmpty())BookCodec.parseBackup(recovery)
                        }
                    }
                    val settings=if(old.exists())reader() else LegacySettings()
                    // The marker, book, PIN verifier and snapshots commit together or not at all.
                    database.runInTransaction {
                        check(dao.state()==null)
                        dao.putState(LedgerState(json=BookCodec.bookJson(book).toString(),revision=revision))
                        if(recovery.isNotEmpty())dao.putRecovery(RecoveryState(json=recovery))
                        settings.preferences.forEach { (k,v)->dao.putPreference(Preference(k,v)) }
                        settings.backups.forEach { dao.putSnapshot(it) }
                    }
                }
                refresh()
            } catch(e: Exception) {
                mutable.value=LedgerSnapshot(failure="迁移或读取未完成，原账目仍保留。${e.message.orEmpty()}")
                throw e
            }
        }
    }
    private fun refresh() {
        val s=dao.state() ?: error("本机账本未初始化")
        mutable.value=LedgerSnapshot(BookCodec.parseBook(s.json),s.revision,dao.preferences().associate { it.key to it.value },dao.snapshots(),dao.recovery()?.json.orEmpty(),true)
    }
    private fun saveAutomatic(book: Book,prefs: Map<String,String>) {
        if(prefs[AUTO]!="true")return
        val backup=Backup(book,DisplayPrefs(if(prefs[THEME]=="dark")"dark" else "light",prefs[FONT]?.takeIf { it in FONT_NAMES }?:"standard"))
        val digest=hash(backup);val existing=dao.snapshots()
        if(existing.firstOrNull()?.hash==digest && existing.none { it.day==today() })return
        if(existing.none { it.day==today() && it.hash==digest })dao.putSnapshot(Snapshot(today(),digest,BookCodec.backupJson(backup)))
        dao.snapshots().drop(7).forEach { dao.removeSnapshot(it.day) }
    }
    suspend fun mutate(action: JSONObject) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(mutable.value.loaded) { "账本尚未读取完成" }
            database.runInTransaction {
                val old=dao.state() ?: error("账本尚未初始化")
                val next=BookActions.apply(BookCodec.parseBook(old.json),action)
                dao.putState(old.copy(json=BookCodec.bookJson(next).toString(),revision=old.revision+1))
                saveAutomatic(next,dao.preferences().associate { it.key to it.value })
            };refresh()
        }
    }
    suspend fun setPreference(key: String,value: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(mutable.value.loaded)
            database.runInTransaction {
                if(value==null)dao.removePreference(key) else dao.putPreference(Preference(key,value))
                if(key in listOf(THEME,FONT,AUTO))saveAutomatic(mutable.value.book,dao.preferences().associate { it.key to it.value })
            };refresh()
        }
    }
    suspend fun restore(backup: Backup,expectedRevision: Long) = withContext(Dispatchers.IO) {
        // Validate before opening the write transaction. Never erase the current state on bad input.
        val checked=BookCodec.parseBackup(BookCodec.backupJson(backup))
        mutex.withLock {
            database.runInTransaction {
                val old=dao.state() ?: error("账本尚未初始化")
                require(old.revision==expectedRevision) { "账本已更新，请重新选择备份" }
                val prefs=dao.preferences().associate { it.key to it.value }
                val recovery=BookCodec.backupJson(Backup(BookCodec.parseBook(old.json),mutable.value.display))
                dao.putState(old.copy(json=BookCodec.bookJson(checked.book).toString(),revision=old.revision+1))
                dao.putRecovery(RecoveryState(json=recovery))
                dao.putPreference(Preference(THEME,checked.preferences.theme));dao.putPreference(Preference(FONT,checked.preferences.fontSize))
                saveAutomatic(checked.book,prefs+mapOf(THEME to checked.preferences.theme,FONT to checked.preferences.fontSize))
            };refresh()
        }
    }
    fun backup(): String { check(state.value.loaded);return BookCodec.backupJson(Backup(state.value.book,state.value.display)) }
    fun close()=database.close()
}
