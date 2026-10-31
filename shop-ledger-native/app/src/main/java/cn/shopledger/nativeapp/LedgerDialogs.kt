package cn.shopledger.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun CompactDialog(title: String,onDismiss: ()->Unit,footer: @Composable ()->Unit,content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),contentAlignment=Alignment.Center) {
            Surface(Modifier.widthIn(max=400.dp).fillMaxWidth().heightIn(max=550.dp),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=6.dp) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {Text(title,Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Bold);IconButton(onClick=onDismiss,modifier=Modifier.size(32.dp)) {Icon(Icons.Default.Close,"关闭",Modifier.size(20.dp))}}
                    Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp),content=content)
                    footer()
                }
            }
        }
    }
}
@Composable private fun FormField(label: String,value: String,onChange: (String)->Unit,numeric: Boolean=false,limit: Int=300,lines: Int=1) {
    OutlinedTextField(value,{raw->onChange((if(numeric)raw.filter {it in '0'..'9' || it=='.'} else raw).take(limit))},Modifier.fillMaxWidth(),label={Text(label,fontSize=13.sp)},singleLine=lines==1,maxLines=lines,textStyle=LocalTextStyle.current.copy(fontSize=15.sp),keyboardOptions=KeyboardOptions(keyboardType=if(numeric)KeyboardType.Decimal else KeyboardType.Text),shape=RoundedCornerShape(10.dp))
}
@Composable internal fun RecordDialog(model: LedgerModel,f: EditForm) {
    val title=when(f.kind) {"entry"->if(f.type=="income")"${if(f.existing)"修改" else "记"}${if(f.incomeKind=="extra")"额外收入" else "固定收入"}" else "${if(f.existing)"修改" else "记"}${categoryName(f.category)}开销";"loan"->if(f.existing)"修改借款" else "记一笔借出";"repay"->"记还钱 · ${f.name}";"reminder"->"添加提醒";"category"->"添加支出分类";else->"店铺名称"}
    CompactDialog(title,{if(!model.busy)model.form=null},footer={
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            if(model.formError.isNotEmpty())Text(model.formError,fontSize=12.sp,color=Expense)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=model::saveForm,enabled=!model.busy,modifier=Modifier.weight(1f).heightIn(min=42.dp),shape=RoundedCornerShape(10.dp)) {Text(if(model.busy)"正在保存…" else if(f.kind in listOf("name","category"))"保存" else "记好这一笔")}
                if(f.kind=="entry" && f.existing)IconButton(enabled=!model.busy,onClick={model.snapshot.book.entries.find {it.id==f.id}?.let(model::deleteEntry)}) {Icon(Icons.Default.DeleteOutline,"删除这笔账",tint=Expense)}
            }
        }
    }) {
        if(f.kind=="entry" && f.type=="income")FormField(if(f.incomeKind=="extra")"是什么收入" else "姓名",f.name,{model.form=f.copy(name=it)},limit=30)
        if(f.kind=="entry" && f.type=="expense" && f.category in listOf("工人工资","其他杂项"))FormField(if(f.category=="工人工资")"姓名" else "具体用途",f.item,{model.form=f.copy(item=it)},limit=30)
        if(f.kind in listOf("loan","name","category","reminder"))FormField(when(f.kind) {"loan"->"谁借了钱？";"category"->"分类名称";"reminder"->"提醒名称";else->"店铺名称"},f.name,{model.form=f.copy(name=it)},limit=if(f.kind=="category")16 else 30)
        if(f.kind=="reminder") {ChoiceRow(listOf("房租" to "房租","工人工资" to "工人工资"),f.reminderKind,{model.form=f.copy(reminderKind=it)});FormField("每月几日（1～31）",f.day,{model.form=f.copy(day=it)},numeric=true,limit=2)}
        if(f.kind !in listOf("name","category","reminder"))DateField(if(f.kind=="repay")"还钱日期" else "日期",f.date,{model.form=f.copy(date=it)},Modifier.fillMaxWidth())
        if(f.kind !in listOf("name","category"))FormField(if(f.kind=="repay")"今天还了几元？" else "多少钱（元）",f.amount,{model.form=f.copy(amount=it)},numeric=true,limit=12)
        if(f.kind in listOf("loan","repay"))LazyRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            items(listOf(5,10,20,50)) { n -> OutlinedButton(onClick={model.form=f.copy(amount=n.toString())},contentPadding=PaddingValues(horizontal=12.dp,vertical=1.dp)) {Text("$n 元",fontSize=12.sp)} }
            if(f.kind=="repay")item {val remaining=model.snapshot.book.activeLoans.find {it.id==f.loanId}?.remaining?:0;OutlinedButton(onClick={model.form=f.copy(amount=decimal(remaining))},contentPadding=PaddingValues(horizontal=10.dp,vertical=1.dp)) {Text("全部还清",fontSize=12.sp)}}
        }
        if(f.kind in listOf("entry","loan")) {
            val detailed=f.kind=="entry" && f.type=="expense" && categoryName(f.category) in DETAILED_CATEGORIES
            FormField(if(detailed)"具体是什么" else "备注（可以不填）",f.note,{model.form=f.copy(note=it)},lines=2)
        }
        if(f.kind=="entry" && f.type=="expense") {
            val frequent=remember(model.snapshot.book,f.category) {frequentItems(model.snapshot.book,categoryName(f.category))}
            if(frequent.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                items(frequent) { value -> SuggestionChip(onClick={model.form=if(f.category in listOf("工人工资","其他杂项"))f.copy(item=value) else f.copy(note=value)},label={Text(value,fontSize=12.sp,maxLines=1)}) }
            }
        }
    }
}

@Composable internal fun PinDialog(model: LedgerModel,mode: String) {
    var pin by remember(mode) {mutableStateOf("")};var repeat by remember(mode) {mutableStateOf("")}
    LaunchedEffect(model.pinError) {if(model.pinError.isNotEmpty())pin=""}
    CompactDialog(if(mode=="setup")"设置隐私密码" else if(mode=="disable")"关闭隐私锁" else "解锁账本",model::cancelPin,footer={
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {if(model.pinError.isNotEmpty())Text(model.pinError,color=Expense,fontSize=12.sp);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedButton(onClick=model::cancelPin,enabled=!model.busy,modifier=Modifier.weight(1f)) {Text("取消")};Button(onClick={model.submitPin(pin,repeat)},enabled=!model.busy,modifier=Modifier.weight(1f).testTag("pin-confirm")) {Text(if(model.busy)"请稍等…" else if(mode=="setup")"开启" else if(mode=="disable")"确认关闭" else "解锁")}}}
    }) {
        Text(if(mode=="setup")"设置 6 位数字密码。请记住密码，密码不随账本备份导出。" else "锁定时仅可查看首页，输入当前 6 位数字密码后继续。",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(pin,{pin=it.filter {c->c in '0'..'9'}.take(6)},Modifier.fillMaxWidth(),label={Text("6位数字密码")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
        if(mode=="setup")OutlinedTextField(repeat,{repeat=it.filter {c->c in '0'..'9'}.take(6)},Modifier.fillMaxWidth(),label={Text("再次输入密码")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
    }
}

private val guideTitles=listOf("从首页，记好第一笔","借出去的钱，单独记","查账，先选时间","备份好，再放心用")
private val guideTexts=listOf("点击蔬菜、房租等支出项目，填写具体是什么、日期和金额，点“记好这一笔”。收入分为固定收入和额外收入。","打开底部“借款”，点“记借出”。收到还款时，直接点列表里的“记还钱”，也可以分次归还。借出和还款不计入总收支。","“记账汇总”看收支和分类统计；“收支明细”点击日期展开当天记录。支持时间、分类筛选和搜索。","设置里可以导出完整 JSON 备份，也可以恢复旧账本。自动备份仍保存在本机，卸载前请先导出文件。还可以开启隐私锁、调整字号和深色模式。")
@Composable internal fun GuideDialog(onFinish: ()->Unit) {
    var page by remember {mutableStateOf(0)}
    CompactDialog("使用教程 · ${page+1}/4",onFinish,footer={Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedButton(onClick={if(page==0)onFinish() else page--},modifier=Modifier.weight(1f)) {Text(if(page==0)"稍后再看" else "上一步")};Button(onClick={if(page==3)onFinish() else page++},modifier=Modifier.weight(1f)) {Text(if(page==3)"开始使用" else "下一步")}};Text("以后可在“设置 → 使用教程”重看",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}) {
        Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {repeat(4) { i->Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(3.dp)).background(if(i<=page)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))}}
        Text(guideTitles[page],fontSize=22.sp,fontWeight=FontWeight.Bold)
        Text(guideTexts[page],fontSize=15.sp,lineHeight=24.sp)
    }
}
