package cn.shopledger.nativeapp

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant

enum class Screen(val title: String) { HOME("小店账本"),LOANS("借款"),SUMMARY("收支"),DETAILS("收支明细"),SETTINGS("设置"),BACKUP("备份与恢复"),EXPORT("导出账本"),TRASH("回收站"),REMINDERS("工资 / 房租提醒") }
data class EditForm(val kind: String,val id: String=newId(),val type: String="expense",val category: String="",val incomeKind: String="fixed",val name: String="",val item: String="",val amount: String="",val date: String=today(),val note: String="",val loanId: String="",val day: String="1",val reminderKind: String="房租",val existing: Boolean=false)
data class Confirmation(val title: String,val description: String,val action: JSONObject,val undo: JSONObject?=null)
data class RestorePreview(val backup: Backup,val filename: String,val revision: Long)
data class Notice(val text: String,val undo: JSONObject?=null)
data class ExportFile(val text: String,val name: String,val mime: String,val complete: Boolean=false)
sealed class FileEvent { data class Export(val file: ExportFile): FileEvent();data object Import: FileEvent() }
fun command(op: String,id: String=newId())=JSONObject().put("op",op).put("id",id)

class LedgerModel(app: Application): AndroidViewModel(app) {
    val repository=LedgerRepository(app)
    var snapshot by mutableStateOf(LedgerSnapshot());private set
    var screen by mutableStateOf(Screen.HOME);private set
    var form by mutableStateOf<EditForm?>(null)
    var formError by mutableStateOf("");private set
    var confirm by mutableStateOf<Confirmation?>(null)
    var restorePreview by mutableStateOf<RestorePreview?>(null);private set
    var busy by mutableStateOf(false);private set
    var locked by mutableStateOf(true);private set
    var pinMode by mutableStateOf<String?>(null);private set
    var pinError by mutableStateOf("");private set
    var tutorial by mutableStateOf(false)
    var pendingExport: ExportFile?=null
    val notices=MutableSharedFlow<Notice>(extraBufferCapacity=8)
    val files=MutableSharedFlow<FileEvent>(extraBufferCapacity=1)
    private var pending: (() -> Unit)?=null
    private var initialized=false
    private var initializing=false
    private var foreground=true
    private var epoch=0
    val pinEnabled get()=snapshot.preferences.containsKey(PIN)
    init { viewModelScope.launch { repository.state.collect { s ->
        val first=s.loaded && !snapshot.loaded
        snapshot=s
        if(first) { locked=s.preferences.containsKey(PIN);tutorial=s.preferences[GUIDE]!="seen" }
        if(!pinEnabled)locked=false else if(!foreground)locked=true
    } } }
    fun initialize() {
        if(initialized || initializing)return
        initializing=true
        viewModelScope.launch { try { repository.initialize();initialized=true } catch(_: Exception) {} finally { initializing=false } }
    }
    fun protect(action: () -> Unit) {
        if(!snapshot.loaded)return
        if(!locked || !pinEnabled)action() else { pending=action;pinError="";pinMode="unlock" }
    }
    fun navigate(next: Screen) { if(next==Screen.HOME)screen=next else protect { screen=next } }
    fun lockForBackground() { foreground=false;epoch++;if(pinEnabled)lockNow() else if(pinMode!=null)cancelPin() }
    fun resume() { foreground=true }
    fun lockNow() { epoch++;if(!pinEnabled)return;locked=true;screen=Screen.HOME;form=null;confirm=null;restorePreview=null;pinMode=null;pending=null }
    fun cancelPin() { if(!busy) { pinMode=null;pending=null;pinError="" } }
    fun togglePin()=protect { pinError="";pinMode=if(pinEnabled)"disable" else "setup" }
    fun submitPin(pin: String,repeat: String) {
        if(busy)return
        val mode=pinMode ?: return;val generation=epoch
        busy=true;pinError=""
        viewModelScope.launch {
            try {
                val throttle=runCatching { JSONObject(snapshot.preferences[THROTTLE]?:"{}") }.getOrDefault(JSONObject())
                val until=throttle.optLong("until",0)
                require(System.currentTimeMillis()>=until) { "请 ${((until-System.currentTimeMillis())/1000+1)} 秒后重试" }
                if(mode=="setup") {
                    require(pin==repeat) { "两次密码不一致" }
                    val config=withContext(Dispatchers.Default) { Privacy.create(pin) }
                    if(generation!=epoch || !foreground)return@launch
                    repository.setPreference(PIN,config)
                } else {
                    val saved=snapshot.preferences[PIN] ?: error("隐私设置无法读取")
                    val valid=withContext(Dispatchers.Default) { Privacy.verify(pin,saved) }
                    if(generation!=epoch || !foreground)return@launch
                    if(!valid) {
                        val count=throttle.optInt("count",0).coerceIn(0,4)+1
                        repository.setPreference(THROTTLE,JSONObject().put("count",if(count>=5)0 else count).put("until",if(count>=5)System.currentTimeMillis()+30000 else 0).toString())
                        error(if(count>=5)"密码错误次数较多，请 30 秒后重试" else "密码不正确")
                    }
                    repository.setPreference(THROTTLE,null)
                    if(mode=="disable")repository.setPreference(PIN,null)
                }
                if(generation!=epoch || !foreground) { lockNow();return@launch }
                locked=false;pinMode=null;val action=pending;pending=null;action?.invoke()
            } catch(e: Exception) { if(generation==epoch)pinError=e.message?:"验证失败，请重试" } finally { busy=false }
        }
    }
    fun preference(key: String,value: String?)=protect { viewModelScope.launch { try { repository.setPreference(key,value) } catch(_: Exception) { notices.emit(Notice("设置未保存，请重试")) } } }
    fun finishGuide() { tutorial=false;viewModelScope.launch { runCatching { repository.setPreference(GUIDE,"seen") } } }
    fun entry(category: String,type: String="expense",incomeKind: String="fixed",existing: Entry?=null)=protect {
        formError="";form=if(existing==null)EditForm("entry",type=type,category=category,incomeKind=incomeKind) else EditForm("entry",id=existing.id,type=existing.type,category=existing.category,incomeKind=existing.incomeKind?:"fixed",name=if(existing.type=="income")existing.category else "",item=existing.item.orEmpty(),amount=decimal(existing.cents),date=existing.date,note=existing.note,existing=true)
    }
    fun loan(existing: Loan?=null)=protect { formError="";form=if(existing==null)EditForm("loan") else EditForm("loan",id=existing.id,name=existing.name,amount=decimal(existing.cents),date=existing.date,note=existing.note,existing=true) }
    fun repay(loan: Loan)=protect { formError="";form=EditForm("repay",loanId=loan.id,name=loan.name) }
    fun edit(kind: String)=protect { formError="";form=EditForm(kind,name=if(kind=="name")snapshot.book.name else "") }
    fun saveForm() {
        val f=form ?: return;if(busy || locked && pinEnabled)return
        try {
            val a=when(f.kind) {
                "entry" -> command("saveEntry",f.id).put("type",f.type).put("category",if(f.type=="income")f.name else f.category).put("incomeKind",f.incomeKind).put("item",f.item).put("cents",parseMoney(f.amount)).put("date",f.date).put("note",f.note)
                "loan" -> command("saveLoan",f.id).put("name",f.name).put("cents",parseMoney(f.amount)).put("date",f.date).put("note",f.note)
                "repay" -> command("repay",f.id).put("loanId",f.loanId).put("cents",parseMoney(f.amount)).put("date",f.date)
                "reminder" -> command("saveReminder",f.id).put("name",f.name).put("cents",parseMoney(f.amount)).put("kind",f.reminderKind).put("day",f.day.toIntOrNull()?:0)
                "category" -> command("addCategory").put("name",f.name)
                else -> command("saveName").put("name",f.name)
            }
            busy=true;formError=""
            viewModelScope.launch { try { repository.mutate(a);form=null;notices.emit(Notice("已记好")) } catch(e: Exception) { formError=e.message?:"保存失败，填写内容已保留" } finally { busy=false } }
        } catch(e: Exception) { formError=e.message?:"请检查填写内容" }
    }
    fun apply(action: JSONObject,notice: String="已保存",undo: JSONObject?=null)=protect {
        if(busy)return@protect;busy=true
        viewModelScope.launch { try { repository.mutate(action);confirm=null;if(action.optString("op").startsWith("delete"))form=null;notices.emit(Notice(notice,undo)) } catch(e: Exception) { notices.emit(Notice(e.message?:"操作未完成，请重试")) } finally { busy=false } }
    }
    fun deleteEntry(e: Entry) { confirm=Confirmation("删除这笔账？","${e.date} · ${e.label} ${e.detail} · ${money(e.cents)} 元。删除后可在回收站恢复。",command("deleteEntry",e.id),command("restoreEntry",e.id)) }
    fun deleteLoan(l: Loan) { confirm=Confirmation("删除这笔借款？","${l.name} · 借出 ${money(l.cents)} 元。收支账本不会受影响，可在回收站恢复。",command("deleteLoan",l.id),command("restoreLoan",l.id)) }
    fun export(kind: String)=protect {
        if(busy)return@protect
        val file=when(kind) {
            "backup"->ExportFile(repository.backup(),"小店账本-${today()}.json","application/json",true)
            "loans"->ExportFile(BookCodec.loansCsv(snapshot.book),"借款-${today()}.csv","text/csv")
            else->ExportFile(BookCodec.entriesCsv(snapshot.book),"收支-${today()}.csv","text/csv")
        }
        pendingExport=file;files.tryEmit(FileEvent.Export(file))
    }
    fun exported(uri: Uri?) {
        val file=pendingExport ?: return;pendingExport=null;if(uri==null)return
        viewModelScope.launch { try {
            withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use { it.write(file.text.toByteArray()) } ?: error("文件无法保存") }
            if(file.complete)repository.setPreference(LAST_BACKUP,Instant.now().toString())
            notices.emit(Notice("文件已保存"))
        } catch(_: Exception) { notices.emit(Notice("文件保存失败，请重新选择位置")) } }
    }
    fun chooseImport()=protect { files.tryEmit(FileEvent.Import) }
    fun exportSnapshot(snapshot: Snapshot)=protect { val file=ExportFile(snapshot.json,"小店账本-${snapshot.day}.json","application/json");pendingExport=file;files.tryEmit(FileEvent.Export(file)) }
    fun imported(uri: Uri?) {
        if(uri==null)return
        viewModelScope.launch { try {
            val text=withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { val count=input.read(buffer);if(count<0)break;out.write(buffer,0,count);require(out.size()<=2_000_000) { "备份最多支持 2 MB" } };out.toString("UTF-8")
            } ?: error("备份无法读取") }
            preview(text,"选中的 JSON 备份")
        } catch(e: Exception) { notices.emit(Notice(e.message?:"备份无法读取")) } }
    }
    fun preview(text: String,name: String) {
        viewModelScope.launch { try {
            val backup=withContext(Dispatchers.IO) { BookCodec.parseBackup(text) }
            protect { restorePreview=RestorePreview(backup,name,snapshot.revision) }
        } catch(e: Exception) { notices.emit(Notice(e.message?:"备份格式无效")) } }
    }
    fun restore()=protect {
        val preview=restorePreview ?: return@protect;if(busy)return@protect;busy=true
        viewModelScope.launch { try { repository.restore(preview.backup,preview.revision);restorePreview=null;notices.emit(Notice("已恢复，恢复前的账本已保留保护备份")) } catch(e: Exception) { notices.emit(Notice(e.message?:"恢复失败，当前账本已保留")) } finally { busy=false } }
    }
    fun cancelRestore() { if(!busy)restorePreview=null }
    override fun onCleared() { super.onCleared();repository.close() }
}
