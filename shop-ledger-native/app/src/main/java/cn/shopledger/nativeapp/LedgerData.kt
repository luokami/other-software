package cn.shopledger.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

val DEFAULT_CATEGORIES = listOf("房租","蔬菜","米面","调料","肉类","礼品","工人工资","生活缴费","其他杂项")
val FONT_NAMES = listOf("tiny","small","standard","large","extra")
val FONT_LABELS = listOf("特小","小","标准","大","特大")
val FONT_SIZES = listOf(14f,17f,20f,21f,22f)
val DETAILED_CATEGORIES = setOf("蔬菜","米面","调料","肉类","生活缴费")
fun newId(): String = UUID.randomUUID().toString()
fun today(): String = LocalDate.now().toString()
fun money(cents: Long): String = String.format(Locale.CHINA,"%,.2f",cents / 100.0)
fun decimal(cents: Long): String = BigDecimal.valueOf(cents,2).toPlainString()
fun categoryName(value: String) = if(value=="水电开销") "生活缴费" else value
fun parseMoney(value: String): Long {
    require(Regex("[0-9]{1,8}(\\.[0-9]{1,2})?").matches(value.trim())) { "金额最多两位小数" }
    val result = value.trim().toBigDecimal().movePointRight(2).longValueExact()
    require(result in 1..9_999_999_999L) { "金额需大于 0，且不超过 99,999,999.99 元" }
    return result
}
data class Entry(val id: String, val type: String, val category: String, val cents: Long, val date: String, val note: String = "", val incomeKind: String? = null, val item: String? = null, val deletedAt: String? = null, val reminderRef: String? = null) {
    val label get() = if(type=="income") if(incomeKind=="extra") "额外收入" else "固定收入" else categoryName(category)
    val detail get() = if(type=="income") category else item?.takeIf { it.isNotBlank() } ?: note
}
data class Payment(val id: String,val cents: Long,val date: String,val deletedAt: String? = null)
data class Loan(val id: String,val name: String,val cents: Long,val date: String,val note: String = "",val payments: List<Payment> = emptyList(),val deletedAt: String? = null) {
    val paid get() = payments.filter { it.deletedAt==null }.sumOf { it.cents }
    val remaining get() = cents-paid
}
data class Reminder(val id: String,val name: String,val kind: String,val cents: Long,val day: Int,val deletedAt: String? = null)
data class Book(val name: String="我的店铺",val entries: List<Entry> = emptyList(),val loans: List<Loan> = emptyList(),val reminders: List<Reminder> = emptyList(),val categories: List<String> = emptyList()) {
    val activeEntries get() = entries.filter { it.deletedAt==null }
    val activeLoans get() = loans.filter { it.deletedAt==null }
    val expenseCategories get() = (DEFAULT_CATEGORIES.dropLast(1)+categories+DEFAULT_CATEGORIES.last()+activeEntries.filter { it.type=="expense" }.map { categoryName(it.category) }).distinct()
}
data class DisplayPrefs(val theme: String="light",val fontSize: String="standard")
data class Backup(val book: Book,val preferences: DisplayPrefs,val createdAt: String=Instant.now().toString())

object BookCodec {
    private fun text(v: Any?,max: Int,empty: Boolean=false): String {
        require(v is String && v.length<=max && (empty || v.isNotBlank())) { "备份文字内容无效" }; return v
    }
    private fun id(v: Any?): String = text(v,80).also { require(Regex("[a-zA-Z0-9:-]+").matches(it)) { "记录编号无效" } }
    private fun amount(v: Any?): Long {
        require(v is Number && v.toDouble().isFinite() && v.toDouble()==v.toLong().toDouble() && v.toLong() in 1..9_999_999_999L) { "备份金额无效" }; return v.toLong()
    }
    fun date(v: Any?): String = text(v,10).also { require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it) && it>="2000-01-01" && it<="2100-12-31" && LocalDate.parse(it).toString()==it) { "日期无效" } }
    private fun timestamp(v: Any?): String = text(v,40).also { Instant.parse(it) }
    private fun deleted(o: JSONObject) = if(o.has("deletedAt")) timestamp(o.get("deletedAt")) else null
    private fun <T> records(a: JSONArray,max: Int,key: (T)->String,parse: (JSONObject)->T): List<T> {
        require(a.length()<=max) { "备份记录数量过多" }
        val result=(0 until a.length()).map { parse(a.getJSONObject(it)) }
        require(result.map(key).distinct().size==result.size) { "备份中存在重复编号" }; return result
    }
    fun parseBook(content: String): Book = parseBook(JSONObject(content))
    fun parseBook(o: JSONObject): Book {
        val entries=records(o.getJSONArray("entries"),10000,{e: Entry->e.id}) { e ->
            val type=text(e.get("type"),10);require(type in listOf("income","expense")) { "收支类型无效" }
            val kind=if(e.has("incomeKind"))text(e.get("incomeKind"),10).also { require(it in listOf("fixed","extra")) } else null
            Entry(id(e.get("id")),type,text(e.get("category"),30),amount(e.get("cents")),date(e.get("date")),text(e.get("note"),300,true),if(type=="income")kind else null,if(e.has("item"))text(e.get("item"),30,true) else null,deleted(e),if(e.has("reminderRef"))text(e.get("reminderRef"),100) else null)
        }
        val loans=records(o.getJSONArray("loans"),1000,{l: Loan->l.id}) { l ->
            val ps=records(l.getJSONArray("payments"),1000,{p: Payment->p.id}) { p -> Payment(id(p.get("id")),amount(p.get("cents")),date(p.get("date")),deleted(p)) }
            Loan(id(l.get("id")),text(l.get("name"),30),amount(l.get("cents")),date(l.get("date")),text(l.get("note"),300,true),ps,deleted(l)).also { loan -> require(loan.paid<=loan.cents && loan.payments.none { it.deletedAt==null && it.date<loan.date }) { "还款记录与借款不一致" } }
        }
        val reminders=records(o.getJSONArray("reminders"),100,{r: Reminder->r.id}) { r ->
            val kind=text(r.get("kind"),10);require(kind in listOf("房租","工人工资"))
            val d=r.get("day");require(d is Number && d.toDouble()==d.toInt().toDouble() && d.toInt() in 1..31) { "提醒日期无效" }
            Reminder(id(r.get("id")),text(r.get("name"),30),kind,amount(r.get("cents")),d.toInt(),deleted(r))
        }
        require(!o.has("categories") || o.get("categories") is JSONArray) { "备份分类格式无效" }
        val array=o.optJSONArray("categories") ?: JSONArray();require(array.length()<=30)
        val categories=(0 until array.length()).map { text(array.get(it),16) };require(categories.distinct().size==categories.size)
        return Book(text(o.get("name"),30),entries,loans,reminders,categories).also { require(bookJson(it).toString().toByteArray().size<=1_500_000) { "账本超过容量" } }
    }
    private fun JSONArray.add(o: JSONObject) = apply { put(o) }
    fun bookJson(b: Book): JSONObject = JSONObject().put("name",b.name)
        .put("entries",JSONArray().apply { b.entries.forEach { e -> put(JSONObject().put("id",e.id).put("type",e.type).put("category",e.category).put("cents",e.cents).put("date",e.date).put("note",e.note).apply { e.incomeKind?.let { put("incomeKind",it) };e.item?.let { put("item",it) };e.deletedAt?.let { put("deletedAt",it) };e.reminderRef?.let { put("reminderRef",it) } }) } })
        .put("loans",JSONArray().apply { b.loans.forEach { l -> put(JSONObject().put("id",l.id).put("name",l.name).put("cents",l.cents).put("date",l.date).put("note",l.note).apply { l.deletedAt?.let { put("deletedAt",it) } }.put("payments",JSONArray().apply { l.payments.forEach { p -> put(JSONObject().put("id",p.id).put("cents",p.cents).put("date",p.date).apply { p.deletedAt?.let { put("deletedAt",it) } }) } })) } })
        .put("reminders",JSONArray().apply { b.reminders.forEach { r -> put(JSONObject().put("id",r.id).put("name",r.name).put("kind",r.kind).put("cents",r.cents).put("day",r.day).apply { r.deletedAt?.let { put("deletedAt",it) } }) } })
        .put("categories",JSONArray(b.categories))
    fun backupJson(b: Backup): String = JSONObject().put("format","xiaodian-ledger-backup").put("version",1).put("createdAt",b.createdAt).put("book",bookJson(b.book)).put("preferences",JSONObject().put("theme",b.preferences.theme).put("fontSize",b.preferences.fontSize)).toString()
    fun parseBackup(content: String): Backup {
        require(content.toByteArray().size<=2_000_000) { "备份最多支持 2 MB" }
        val o=JSONObject(content);require(o.optString("format")=="xiaodian-ledger-backup" && o.get("version") is Number && (o.get("version") as Number).toDouble()==1.0) { "请选择小店账本的完整 JSON 备份" }
        val p=o.getJSONObject("preferences");val theme=text(p.get("theme"),10);val font=text(p.get("fontSize"),10)
        require(theme in listOf("light","dark") && font in FONT_NAMES) { "备份显示设置无效" }
        return Backup(parseBook(o.getJSONObject("book")),DisplayPrefs(theme,font),timestamp(o.get("createdAt")))
    }
    fun entriesCsv(b: Book): String = csv(listOf(listOf("日期","收支类型","分类","项目或姓名","金额（元）","备注"))+b.activeEntries.sortedByDescending { it.date }.map { listOf(it.date,if(it.type=="income")"收入" else "支出",it.label,if(it.type=="income")it.category else it.item.orEmpty(),decimal(it.cents),it.note) })
    fun loansCsv(b: Book): String = csv(listOf(listOf("姓名","借出日期","借出金额（元）","已还金额（元）","剩余欠款（元）","是否结清","备注","还款明细","借款编号"))+b.activeLoans.sortedByDescending { it.date }.map { l -> listOf(l.name,l.date,decimal(l.cents),decimal(l.paid),decimal(l.remaining),if(l.remaining==0L)"已结清" else "未结清",l.note,l.payments.filter { it.deletedAt==null }.joinToString("；") { "${it.date}：${decimal(it.cents)}元" },l.id) })
    private fun csv(rows: List<List<String>>) = "\uFEFF"+rows.joinToString("\r\n",postfix="\r\n") { row -> row.joinToString(",") { value -> val safe=if(Regex("^\\s*[=+\\-@\\t\\r]").containsMatchIn(value))"'$value" else value;"\""+safe.replace("\"","\"\"")+"\"" } }
}

object BookActions {
    private fun text(o: JSONObject,key: String,label: String,max: Int=30): String = o.optString(key).trim().also { require(it.isNotEmpty() && it.length<=max) { "$label 不能为空，最多 $max 个字" } }
    fun apply(previous: Book,a: JSONObject): Book {
        val id=a.optString("id","none");require(Regex("[a-zA-Z0-9:-]{1,80}").matches(id))
        val now=Instant.now().toString()
        val result=when(a.getString("op")) {
            "saveEntry" -> {
                val type=a.getString("type");require(type in listOf("income","expense"))
                val old=previous.entries.find { it.id==id };val kind=a.optString("incomeKind",old?.incomeKind?:"fixed")
                require(type!="income" || kind in listOf("fixed","extra"));require(old?.reminderRef==null || type=="expense") { "提醒付款只能记为开销" }
                val cat=text(a,"category",if(type=="income")"收入来源或姓名" else "项目")
                val item=if(type=="expense" && cat in listOf("工人工资","其他杂项"))text(a,"item",if(cat=="工人工资")"姓名" else "具体用途") else a.optString("item").trim().takeIf { it.isNotEmpty() }
                val note=a.optString("note").trim();require(note.length<=300) { "备注最多 300 字" }
                if(type=="expense" && categoryName(cat) in DETAILED_CATEGORIES)require(note.isNotBlank()) { "请填写具体是什么" }
                val e=Entry(id,type,cat,a.getLong("cents"),BookCodec.date(a.get("date")),note,if(type=="income")kind else null,item,reminderRef=old?.reminderRef)
                require(old!=null || previous.entries.size<10000) { "账目已达到 10,000 笔" }
                previous.copy(entries=if(old==null)listOf(e)+previous.entries else previous.entries.map { if(it.id==id)e else it })
            }
            "deleteEntry","restoreEntry" -> {
                require(previous.entries.any { it.id==id }) { "找不到这笔账" }
                previous.copy(entries=previous.entries.map { e -> if(e.id!=id)e else if(a.getString("op")=="deleteEntry")e.copy(deletedAt=now) else { require(e.deletedAt==null || Instant.now().toEpochMilli()-Instant.parse(e.deletedAt).toEpochMilli()<=30L*86400000) { "已超过 30 天恢复期限" };e.copy(deletedAt=null) } })
            }
            "saveLoan" -> {
                val old=previous.loans.find { it.id==id };val date=BookCodec.date(a.get("date"));val cents=a.getLong("cents")
                require(old==null || old.paid<=cents) { "借款金额不能小于已还金额" }
                require(old==null || old.payments.none { it.deletedAt==null && it.date<date }) { "借出日期不能晚于已有还款" }
                require(old!=null || previous.loans.size<1000) { "借款最多 1,000 笔" }
                val loan=Loan(id,text(a,"name","姓名"),cents,date,a.optString("note").trim(),old?.payments?:emptyList())
                previous.copy(loans=if(old==null)listOf(loan)+previous.loans else previous.loans.map { if(it.id==id)loan else it })
            }
            "repay" -> {
                val loan=previous.loans.find { it.id==a.getString("loanId") && it.deletedAt==null } ?: error("找不到借款")
                val cents=a.getLong("cents");val date=BookCodec.date(a.get("date"));require(date>=loan.date) { "还钱日期不能早于借出日期" }
                val old=loan.payments.find { it.id==id }
                if(old!=null) { require(old.cents==cents && old.date==date && old.deletedAt==null) { "还款编号已使用" };return previous }
                require(cents<=loan.remaining) { "还钱金额超过剩余欠款" };require(loan.payments.size<1000)
                previous.copy(loans=previous.loans.map { if(it.id==loan.id)it.copy(payments=it.payments+Payment(id,cents,date)) else it })
            }
            "undoPayment" -> {
                val loan=previous.loans.find { it.id==a.getString("loanId") } ?: error("找不到借款")
                require(loan.payments.any { it.id==id }) { "找不到还款" }
                previous.copy(loans=previous.loans.map { if(it.id==loan.id)it.copy(payments=it.payments.map { p -> if(p.id==id)p.copy(deletedAt=now) else p }) else it })
            }
            "deleteLoan","restoreLoan" -> {
                require(previous.loans.any { it.id==id });previous.copy(loans=previous.loans.map { if(it.id==id)it.copy(deletedAt=if(a.getString("op")=="deleteLoan")now else null) else it })
            }
            "addCategory" -> {
                val name=text(a,"name","分类名称",16);require(name !in DEFAULT_CATEGORIES && name!="水电开销" && name !in previous.categories) { "已经有这个分类" };require(previous.categories.size<30)
                previous.copy(categories=previous.categories+name)
            }
            "removeCategory" -> {val name=text(a,"name","分类名称",16);require(name !in DEFAULT_CATEGORIES && name!="水电开销");previous.copy(categories=previous.categories.filter { it!=name })}
            "saveName" -> previous.copy(name=text(a,"name","店铺名称"))
            "saveReminder" -> {
                val r=Reminder(id,text(a,"name","提醒名称"),a.getString("kind"),a.getLong("cents"),a.getInt("day"))
                val exists=previous.reminders.any { it.id==id };require(exists || previous.reminders.size<100)
                previous.copy(reminders=if(exists)previous.reminders.map { if(it.id==id)r else it } else previous.reminders+r)
            }
            "deleteReminder" -> {require(previous.reminders.any { it.id==id });previous.copy(reminders=previous.reminders.map { if(it.id==id)it.copy(deletedAt=now) else it })}
            "confirmReminder" -> {
                val r=previous.reminders.find { it.id==id && it.deletedAt==null } ?: error("找不到提醒")
                val date=BookCodec.date(a.get("date"));val ref="${r.id}:${date.take(7)}"
                if(previous.activeEntries.any { it.reminderRef==ref })return previous
                require(previous.entries.size<10000)
                previous.copy(entries=listOf(Entry(a.getString("entryId"),"expense",r.kind,r.cents,date,r.name,reminderRef=ref))+previous.entries)
            }
            else -> error("不支持的操作")
        }
        return BookCodec.parseBook(BookCodec.bookJson(result))
    }
}

fun matches(entry: Entry,query: String): Boolean = query.trim().lowercase().let { q -> q.isEmpty() || listOf(entry.category,entry.label,entry.item.orEmpty(),entry.note,entry.date,money(entry.cents),decimal(entry.cents)).any { it.lowercase().contains(q) } }
fun matches(loan: Loan,query: String): Boolean = query.trim().lowercase().let { q -> q.isEmpty() || listOf(loan.name,loan.note,loan.date,money(loan.cents),money(loan.remaining),decimal(loan.cents),decimal(loan.remaining)).any { it.lowercase().contains(q) } }
fun inPeriod(date: String,period: String,start: String,end: String,now: String=today()) = when(period) { "today"->date==now;"month"->date.startsWith(now.take(7));"year"->date.startsWith(now.take(4));else->start<=end && date>=start && date<=end }
fun frequentItems(book: Book,category: String): List<String> = book.activeEntries.filter { it.type=="expense" && categoryName(it.category)==categoryName(category) }.mapNotNull { if(category in listOf("工人工资","其他杂项"))it.item?.takeIf(String::isNotBlank) else it.note.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key }
fun dueReminders(book: Book) = book.reminders.filter { r -> r.deletedAt==null && LocalDate.now().dayOfMonth>=minOf(r.day,LocalDate.now().lengthOfMonth()) && book.activeEntries.none { it.reminderRef=="${r.id}:${today().take(7)}" } }

object Privacy {
    fun parse(value: String): JSONObject = JSONObject(value).also { require(it.getInt("version")==1 && it.getInt("iterations")==200000 && Regex("[a-f0-9]{32}").matches(it.getString("salt")) && Regex("[a-f0-9]{64}").matches(it.getString("verifier"))) { "隐私锁设置无法读取" } }
    private fun bytes(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun derive(pin: String,salt: ByteArray): ByteArray {
        val spec=PBEKeySpec(pin.toCharArray(),salt,200000,256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun create(pin: String): String {
        require(Regex("[0-9]{6}").matches(pin)) { "请设置 6 位数字密码" }
        val salt=ByteArray(16).also { SecureRandom().nextBytes(it) }
        return JSONObject().put("version",1).put("salt",hex(salt)).put("verifier",hex(derive(pin,salt))).put("iterations",200000).toString()
    }
    fun verify(pin: String,value: String): Boolean {
        if(!Regex("[0-9]{6}").matches(pin))return false
        val config=parse(value);return MessageDigest.isEqual(derive(pin,bytes(config.getString("salt"))),bytes(config.getString("verifier")))
    }
}
