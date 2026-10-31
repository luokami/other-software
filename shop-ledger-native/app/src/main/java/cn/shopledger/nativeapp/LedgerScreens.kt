package cn.shopledger.nativeapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import java.time.Instant
import kotlin.math.roundToInt

@Composable internal fun LoansPage(model: LedgerModel) {
    val book=model.snapshot.book
    var filter by rememberSaveable {mutableStateOf("open")};var query by rememberSaveable {mutableStateOf("")}
    val loans=book.activeLoans.filter { (filter=="all" || filter=="closed" && it.remaining==0L || filter=="open" && it.remaining>0L) && matches(it,query) }
    val received=book.activeLoans.sumOf {l->l.payments.filter {it.deletedAt==null && it.date==today()}.sumOf {it.cents}}
    val settled=book.activeLoans.filter {it.remaining==0L}.sumOf {l->l.payments.filter {it.deletedAt==null && it.date==today()}.sumOf {it.cents}}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(bottom=12.dp)) {
        item {Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {listOf(Triple("还没结清",book.activeLoans.sumOf {it.remaining},Expense),Triple("今天收回",received,Income),Triple("今天结清",settled,Blue)).forEach { (name,cents,color)->Panel(Modifier.weight(1f)) {Text(name,fontSize=12.sp);MoneyText(cents,color=color,size=16)} }} }
        item {Text("借出和还钱独立记录，不计入总收支。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {SearchField(query,{query=it},"搜索姓名、备注或金额")}
        item {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {Box(Modifier.weight(1f)) {ChoiceRow(listOf("open" to "未结清","closed" to "已结清","all" to "全部"),filter,{filter=it})};FilledTonalButton(onClick={model.loan()},contentPadding=PaddingValues(9.dp)) {Icon(Icons.Default.Add,null,Modifier.size(17.dp));Text("记借出",fontSize=13.sp)} }}
        items(loans,key={it.id}) { loan -> LoanLine(model,loan) }
        if(loans.isEmpty())item {Empty(if(query.isBlank())"暂无符合条件的借款" else "没有匹配的借款")}
    }
}
@Composable private fun LoanLine(model: LedgerModel,loan: Loan) {
    var expanded by rememberSaveable(loan.id) {mutableStateOf(false)}
    var paymentLimit by remember(loan.id) {mutableStateOf(10)}
    Panel {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(Modifier.weight(1f).clickable {expanded=!expanded}.heightIn(min=30.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(loan.name,Modifier.weight(1f),fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold)
                MoneyText(loan.remaining,Modifier.widthIn(max=103.dp),if(loan.remaining>0)Expense else Income,"¥ ",15)
                Text(if(loan.remaining>0)"未结清" else "已结清",fontSize=10.sp,color=if(loan.remaining>0)Expense else Income)
                Icon(if(expanded)Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,"展开借款详情",Modifier.size(14.dp))
            }
            if(loan.remaining>0)FilledTonalButton(onClick={model.repay(loan)},enabled=!model.busy,contentPadding=PaddingValues(horizontal=7.dp,vertical=0.dp),modifier=Modifier.heightIn(min=34.dp)) {Text("记还钱",fontSize=12.sp)}
        }
        AnimatedVisibility(expanded) {Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            HorizontalDivider()
            Text("${loan.date} · 借出 ${money(loan.cents)} 元",fontSize=12.sp)
            if(loan.note.isNotBlank())Text("备注：${loan.note}",fontSize=12.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                TextButton(onClick={model.loan(loan)},enabled=!model.busy,contentPadding=PaddingValues(4.dp)) {Text("修改",fontSize=12.sp)}
                TextButton(onClick={model.deleteLoan(loan)},enabled=!model.busy,contentPadding=PaddingValues(4.dp)) {Text("删除",fontSize=12.sp,color=Expense)}
            }
            val payments=loan.payments.filter {it.deletedAt==null}
            payments.take(paymentLimit).forEach { p -> Row(verticalAlignment=Alignment.CenterVertically) {
                Text(p.date,Modifier.weight(1f),fontSize=12.sp);MoneyText(p.cents,color=Income,prefix="已还 ",size=13)
                TextButton(onClick={model.confirm=Confirmation("撤销这次还钱？","${p.date} · ${money(p.cents)} 元。撤销后重新计算剩余欠款。",command("undoPayment",p.id).put("loanId",loan.id))},enabled=!model.busy,contentPadding=PaddingValues(4.dp)) {Text("撤销",fontSize=11.sp)}
            } }
            if(payments.size>paymentLimit)TextButton(onClick={paymentLimit+=20}) {Text("查看更多还款（共 ${payments.size} 笔）",fontSize=12.sp)}
        } }
    }
}

@Composable private fun SettingRow(icon: ImageVector,label: String,value: String="",onClick: (() -> Unit)?=null,trailing: (@Composable ()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(onClick=onClick) else Modifier).padding(vertical=9.dp).heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(icon,null,Modifier.size(21.dp),tint=MaterialTheme.colorScheme.primary)
        Text(label,Modifier.weight(1f),fontSize=15.sp)
        if(value.isNotEmpty())Text(value,Modifier.widthIn(max=115.dp),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(trailing!=null)trailing() else if(onClick!=null)Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(17.dp))
    }
}
@Composable internal fun SettingsPage(model: LedgerModel) {
    val state=model.snapshot;val prefs=state.preferences
    var font by remember(state.display.fontSize) {mutableStateOf(FONT_NAMES.indexOf(state.display.fontSize).coerceAtLeast(0).toFloat())}
    LazyColumn(modifier=Modifier.testTag("settings-list"),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=12.dp)) {
        item {Section("字号与显示")}
        item {Panel {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("字号");Text(FONT_LABELS[font.roundToInt()],color=MaterialTheme.colorScheme.primary)}
            Slider(value=font,onValueChange={font=it.roundToInt().toFloat()},onValueChangeFinished={model.preference(FONT,FONT_NAMES[font.roundToInt()])},valueRange=0f..4f,steps=3,modifier=Modifier.fillMaxWidth().semantics {contentDescription="调节字体大小"})
            Row {FONT_LABELS.forEachIndexed { i,name->TextButton(onClick={font=i.toFloat();model.preference(FONT,FONT_NAMES[i])},modifier=Modifier.weight(1f),contentPadding=PaddingValues(1.dp)) {Text(name,fontSize=12.sp,maxLines=1)} }}
            ChoiceRow(listOf("light" to "浅色","dark" to "深色"),state.display.theme,{model.preference(THEME,it)})
        } }
        item {Section("数据安全")}
        item {Panel {
            SettingRow(Icons.Default.Backup,"自动备份",trailing={Switch(checked=prefs[AUTO]=="true",onCheckedChange={model.preference(AUTO,it.toString())},enabled=!model.busy,modifier=Modifier.semantics {contentDescription="自动备份"})})
            HorizontalDivider();SettingRow(Icons.Default.Folder,"备份与恢复",if(prefs[LAST_BACKUP].isNullOrEmpty())"未导出" else "已导出",{model.navigate(Screen.BACKUP)})
            HorizontalDivider();SettingRow(Icons.Default.Download,"导出账本","CSV",{model.navigate(Screen.EXPORT)})
            HorizontalDivider();SettingRow(Icons.Default.DeleteOutline,"回收站","${recycledEntries(state.book).size+state.book.loans.count {it.deletedAt!=null}} 笔",{model.navigate(Screen.TRASH)})
        } }
        item {Section("隐私")}
        item {Panel {SettingRow(Icons.Default.Lock,"隐私锁",trailing={Switch(checked=model.pinEnabled,onCheckedChange={model.togglePin()},enabled=!model.busy,modifier=Modifier.semantics {contentDescription="隐私锁"})});Text("开启后，返回后台自动锁定；未解锁仅可查看首页。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)} }
        item {Section("店铺")}
        item {Panel {SettingRow(Icons.Default.Store,"店铺名称",state.book.name,{model.edit("name")})} }
        item {Section("提醒")}
        item {Panel {SettingRow(Icons.Default.Notifications,"工资 / 房租提醒",dueReminders(state.book).size.takeIf {it>0}?.let {"$it 项到期"}.orEmpty(),{model.navigate(Screen.REMINDERS)})} }
        item {Section("帮助")}
        item {Panel {SettingRow(Icons.Default.MenuBook,"使用教程",onClick={model.tutorial=true});HorizontalDivider();Text("原生版 1.3.0 · 本机离线账本",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)} }
    }
}

@Composable internal fun BackupPage(model: LedgerModel) {
    val state=model.snapshot
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=12.dp)) {
        item {Panel {
            Text("完整备份包含收支、借款与还款、回收站、提醒、自定义分类及显示设置。",fontSize=14.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Button(onClick={model.export("backup")},modifier=Modifier.weight(1f)) {Text("完整备份",fontSize=13.sp)};OutlinedButton(onClick=model::chooseImport,modifier=Modifier.weight(1f)) {Text("恢复备份",fontSize=13.sp)}}
            Text("JSON 用于恢复。密码不随账本导出，请妥善保存文件。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if(state.recovery.isNotEmpty())item {Panel {Text("恢复前保护备份",fontWeight=FontWeight.SemiBold);Text("保留上一次恢复前的账本。",fontSize=12.sp);OutlinedButton(onClick={model.preview(state.recovery,"恢复前保护备份")}) {Text("查看并恢复")}} }
        item {Section("本机自动备份")}
        item {Text("${if(state.preferences[AUTO]=="true")"已开启" else "已关闭"}，保留最近 7 个有变化日期的备份。本机备份会随卸载或清除应用数据而删除，请定期导出文件。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(state.backups,key={it.day}) { record -> Panel {Row(verticalAlignment=Alignment.CenterVertically) {Text(record.day,Modifier.weight(1f),fontSize=14.sp);TextButton(onClick={model.exportSnapshot(record)},enabled=!model.busy,contentPadding=PaddingValues(4.dp)) {Text("导出",fontSize=12.sp)};TextButton(onClick={model.preview(record.json,"${record.day} 自动备份")},enabled=!model.busy,contentPadding=PaddingValues(4.dp)) {Text("恢复",fontSize=12.sp)}}} }
        if(state.backups.isEmpty())item {Empty("暂无自动备份")}
    }
}
@Composable internal fun ExportPage(model: LedgerModel) {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("导出全部有效记录为 CSV，可用表格软件查看。",fontSize=14.sp)
        Panel {SettingRow(Icons.Default.Description,"导出收支","${model.snapshot.book.activeEntries.size} 笔",{model.export("entries")});HorizontalDivider();SettingRow(Icons.Default.People,"导出借款","${model.snapshot.book.activeLoans.size} 笔",{model.export("loans")})}
        Text("借款独立导出，不计入收支。恢复账本请使用完整 JSON 备份。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
private fun recycledEntries(book: Book)=book.entries.filter {it.deletedAt!=null && Instant.now().toEpochMilli()-Instant.parse(it.deletedAt).toEpochMilli()<=30L*86400000}.sortedByDescending {it.deletedAt}
@Composable internal fun TrashPage(model: LedgerModel) {
    var type by rememberSaveable {mutableStateOf("entries")};val book=model.snapshot.book
    LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(bottom=12.dp)) {
        item {ChoiceRow(listOf("entries" to "收支","loans" to "借款"),type,{type=it})}
        if(type=="entries") {
            item {Text("收支删除后 30 天内可以恢复。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            val entries=recycledEntries(book)
            items(entries,key={it.id}) { e -> Panel {Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("${e.label} ${e.detail}",fontSize=14.sp);Text("${e.date} · ${money(e.cents)} 元",fontSize=12.sp)};TextButton(onClick={model.apply(command("restoreEntry",e.id),"已恢复")},enabled=!model.busy) {Text("恢复")}}} }
            if(entries.isEmpty())item {Empty("没有可恢复的收支")}
        } else {
            val loans=book.loans.filter {it.deletedAt!=null}.sortedByDescending {it.deletedAt}
            items(loans,key={it.id}) { l -> Panel {Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text(l.name,fontSize=14.sp);Text("${l.date} · 借出 ${money(l.cents)} 元",fontSize=12.sp)};TextButton(onClick={model.apply(command("restoreLoan",l.id),"已恢复")},enabled=!model.busy) {Text("恢复")}}} }
            if(loans.isEmpty())item {Empty("没有可恢复的借款")}
        }
    }
}
@Composable internal fun RemindersPage(model: LedgerModel) {
    val book=model.snapshot.book;val reminders=book.reminders.filter {it.deletedAt==null}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp),contentPadding=PaddingValues(bottom=12.dp)) {
        item {Text("打开账本时检查到期提醒。确认已付款后才记为开销；不会自动扣款或重复记账。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        item {Button(onClick={model.edit("reminder")},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Default.Add,null);Text("添加提醒")}}
        items(reminders,key={it.id}) { r -> val paid=book.activeEntries.any {it.reminderRef=="${r.id}:${today().take(7)}"};Panel {
            Row(verticalAlignment=Alignment.CenterVertically) {Text(r.name,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);MoneyText(r.cents,prefix="¥ ")}
            Text("${r.kind} · 每月 ${r.day} 日",fontSize=12.sp)
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={model.confirm=Confirmation("确认已经付款？","将 ${r.name} 的 ${money(r.cents)} 元记入本月开销。",command("confirmReminder",r.id).put("date",today()).put("entryId",newId()))},enabled=!paid && !model.busy,modifier=Modifier.weight(1f)) {Text(if(paid)"本月已记" else "已付，记为开销",fontSize=13.sp)}
                TextButton(onClick={model.confirm=Confirmation("删除提醒？","已有收支记录会保留。",command("deleteReminder",r.id))},enabled=!model.busy) {Text("删除",color=Expense,fontSize=12.sp)}
            }
        } }
        if(reminders.isEmpty())item {Empty("还没有设置提醒")}
    }
}
