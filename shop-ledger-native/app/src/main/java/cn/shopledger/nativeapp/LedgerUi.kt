@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cn.shopledger.nativeapp

import android.app.Activity
import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal val Income=Color(0xFF27815C)
internal val Expense=Color(0xFFDB6571)
internal val Blue=Color(0xFF80ACF5)
private val LightColors=lightColorScheme(primary=Color(0xFF2459E0),background=Color(0xFFF4F6FA),surface=Color.White,surfaceVariant=Color(0xFFEAF0F8),primaryContainer=Color(0xFFE1ECFF))
private val DarkColors=darkColorScheme(primary=Color(0xFF8AB5FF),background=Color(0xFF0F1722),surface=Color(0xFF1E2B3C),surfaceVariant=Color(0xFF26374C),primaryContainer=Color(0xFF2B4468))
private val appScreens=listOf(Screen.HOME,Screen.LOANS,Screen.SUMMARY,Screen.DETAILS,Screen.SETTINGS)
private val appIcons=listOf(Icons.Default.Home,Icons.Default.People,Icons.Default.MenuBook,Icons.Default.Description,Icons.Default.Settings)
private val appLabels=listOf("首页","借款","记账汇总","收支明细","设置")

@Composable fun LedgerApp(model: LedgerModel) {
    val snapshot=model.snapshot;val density=LocalDensity.current
    val font=FONT_NAMES.indexOf(snapshot.display.fontSize).coerceAtLeast(0)
    val dark=snapshot.display.theme=="dark"
    val window=(LocalContext.current as? Activity)?.window
    SideEffect { if(window!=null) { WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightStatusBars=!dark;WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightNavigationBars=!dark } }
    CompositionLocalProvider(LocalDensity provides Density(density.density,density.fontScale*FONT_SIZES[font]/20f)) {
        MaterialTheme(colorScheme=if(dark)DarkColors else LightColors) {
            val snack=remember { SnackbarHostState() }
            LaunchedEffect(model) { model.notices.collect { notice ->
                val result=snack.showSnackbar(notice.text,actionLabel=if(notice.undo!=null)"撤销" else null,duration=SnackbarDuration.Short)
                if(result==SnackbarResult.ActionPerformed)notice.undo?.let { model.apply(it,"已恢复") }
            } }
            val screen=if(model.locked && model.pinEnabled)Screen.HOME else model.screen
            BackHandler(enabled=model.tutorial || model.form!=null || model.confirm!=null || model.restorePreview!=null || model.pinMode!=null || screen!=Screen.HOME) {
                when { model.pinMode!=null->model.cancelPin();model.tutorial->model.finishGuide();model.form!=null->{if(!model.busy)model.form=null};model.confirm!=null->{if(!model.busy)model.confirm=null};model.restorePreview!=null->{if(!model.busy)model.cancelRestore()};screen in listOf(Screen.BACKUP,Screen.EXPORT,Screen.TRASH,Screen.REMINDERS)->model.navigate(Screen.SETTINGS);else->model.navigate(Screen.HOME) }
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                if(!snapshot.loaded) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        if(snapshot.failure.isEmpty())CircularProgressIndicator()
                        Text(if(snapshot.failure.isEmpty())"正在读取账本，首次升级会迁移旧数据…" else "账本读取未完成，原数据已保留。请重试。")
                        if(snapshot.failure.isNotEmpty())Button(onClick=model::initialize) { Text("重试") }
                    }
                } else Scaffold(containerColor=MaterialTheme.colorScheme.background,contentWindowInsets=WindowInsets(0,0,0,0),snackbarHost={ SnackbarHost(snack) },bottomBar={
                    Surface(Modifier.padding(horizontal=12.dp,vertical=6.dp).fillMaxWidth(),shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp) {
                        Row(Modifier.padding(4.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                            appScreens.forEachIndexed { i,target -> val selected=screen==target || target==Screen.SETTINGS && screen !in appScreens
                                Column(Modifier.weight(1f).heightIn(min=48.dp).clip(RoundedCornerShape(22.dp)).background(if(selected)MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { model.navigate(target) }.padding(vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                    Icon(appIcons[i],appLabels[i],Modifier.size(21.dp),tint=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(appLabels[i],fontSize=10.sp,maxLines=1,color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }) { inset ->
                    Column(Modifier.padding(inset).consumeWindowInsets(inset).fillMaxSize().imePadding().padding(horizontal=14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(screen !in appScreens)IconButton(onClick={model.navigate(Screen.SETTINGS)},modifier=Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回设置") }
                            Column(Modifier.weight(1f)) { Text(screen.title,fontSize=23.sp,fontWeight=FontWeight.Bold);if(screen==Screen.HOME)Text(snapshot.book.name,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            if(model.pinEnabled)OutlinedButton(onClick={if(model.locked)model.protect {} else model.lockNow()},contentPadding=PaddingValues(horizontal=10.dp,vertical=2.dp)) { Icon(Icons.Default.Lock,null,Modifier.size(14.dp));Text(if(model.locked)"解锁" else "锁定",fontSize=12.sp) }
                        }
                        when(screen) {
                            Screen.HOME->HomePage(model)
                            Screen.LOANS->LoansPage(model)
                            Screen.SUMMARY->SummaryPage(model)
                            Screen.DETAILS->DetailsPage(model)
                            Screen.SETTINGS->SettingsPage(model)
                            Screen.BACKUP->BackupPage(model)
                            Screen.EXPORT->ExportPage(model)
                            Screen.TRASH->TrashPage(model)
                            Screen.REMINDERS->RemindersPage(model)
                        }
                    }
                }
            }
            if(model.tutorial && snapshot.loaded)GuideDialog(model::finishGuide)
            if(!model.locked || !model.pinEnabled) {
                model.form?.let { RecordDialog(model,it) }
                model.confirm?.let { c -> AlertDialog(onDismissRequest={if(!model.busy)model.confirm=null},title={Text(c.title)},text={Text(c.description)},confirmButton={TextButton(enabled=!model.busy,onClick={model.apply(c.action,"已保存",c.undo)}) { Text("确认",color=Expense) }},dismissButton={TextButton(enabled=!model.busy,onClick={model.confirm=null}) { Text("取消") }}) }
                model.restorePreview?.let { p -> AlertDialog(onDismissRequest={if(!model.busy)model.cancelRestore()},title={Text("恢复这份备份？")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(p.filename);Text("收支 ${p.backup.book.activeEntries.size} 笔 · 借款 ${p.backup.book.activeLoans.size} 笔");Text("这会替换当前账本与显示设置，不合并记录。恢复前会自动保留当前账本的保护备份。") }},confirmButton={TextButton(enabled=!model.busy,onClick=model::restore) {Text(if(model.busy)"正在恢复…" else "确认恢复")}},dismissButton={TextButton(enabled=!model.busy,onClick=model::cancelRestore) {Text("取消")}}) }
            }
            model.pinMode?.let { PinDialog(model,it) }
        }
    }
}

@Composable internal fun Panel(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Surface(modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=1.dp) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content) }
}
@Composable internal fun MoneyText(cents: Long,modifier: Modifier=Modifier,color: Color=MaterialTheme.colorScheme.onSurface,prefix: String="",size: Int=16) {
    var textSize by remember(cents,size) { mutableStateOf(size.toFloat()) }
    Text(prefix+money(cents),modifier,fontSize=textSize.sp,color=color,fontWeight=FontWeight.SemiBold,maxLines=1,softWrap=false,onTextLayout={if(it.hasVisualOverflow && textSize>10)textSize=(textSize*.9f).coerceAtLeast(10f)})
}
@Composable internal fun Empty(text: String) { Text(text,Modifier.fillMaxWidth().padding(vertical=25.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp) }
@Composable internal fun Section(text: String) {Text(text,Modifier.padding(top=6.dp,bottom=2.dp),fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
@Composable private fun HomePage(model: LedgerModel) {
    val book=model.snapshot.book
    LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=10.dp)) {
        item { Panel { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("记录状态",fontSize=14.sp);Text("本机已保存",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)};Row { Column(Modifier.weight(1f)) {Text("今日已记",fontSize=12.sp);Text("${book.activeEntries.count { it.date==today() }} 笔",fontSize=18.sp)};Column(Modifier.weight(1f)) {Text("借款记录",fontSize=12.sp);Text("${book.activeLoans.size} 笔",fontSize=18.sp)} } } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("固定收入" to "fixed","额外收入" to "extra").forEach { (name,kind)->Panel(Modifier.weight(1f).clickable { model.entry(name,"income",kind) }) { Row(Modifier.heightIn(min=36.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {Text(if(kind=="fixed")"💵" else "💰",fontSize=23.sp);Text(name,fontSize=15.sp,fontWeight=FontWeight.SemiBold)} } } } }
        item { Panel {
            Text("支出项目",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
            val categories=DEFAULT_CATEGORIES.dropLast(1)+book.categories+DEFAULT_CATEGORIES.last()+"添加分类"
            categories.chunked(3).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                row.forEach { category -> Box(Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { if(category=="添加分类")model.edit("category") else model.entry(category) }) {
                    Column(Modifier.align(Alignment.Center).padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally) {Text(when(category) {"房租"->"🏠";"蔬菜"->"🥬";"米面"->"🍚";"调料"->"🧂";"肉类"->"🥩";"礼品"->"🎁";"工人工资"->"👷";"生活缴费"->"💡";"添加分类"->"＋";else->"🧾"},fontSize=23.sp);Text(category,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
                    if(category in book.categories)IconButton(onClick={model.protect { model.confirm=Confirmation("删除“$category”分类？","已有账目会保留，可撤销。",command("removeCategory").put("name",category),command("addCategory").put("name",category)) }},modifier=Modifier.align(Alignment.TopEnd).size(26.dp)) {Icon(Icons.Default.Close,"删除${category}分类",Modifier.size(14.dp))}
                } }
                repeat(3-row.size) { Spacer(Modifier.weight(1f)) }
            } }
        } }
    }
}

@Composable internal fun ChoiceRow(options: List<Pair<String,String>>,value: String,onChange: (String)->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
        options.forEach { (key,label) -> TextButton(onClick={onChange(key)},modifier=Modifier.weight(1f).heightIn(min=36.dp).clip(RoundedCornerShape(9.dp)).background(if(value==key)MaterialTheme.colorScheme.primaryContainer else Color.Transparent),contentPadding=PaddingValues(3.dp)) {Text(label,fontSize=13.sp,maxLines=1)} }
    }
}
@Composable internal fun DateField(label: String,value: String,onChange: (String)->Unit,modifier: Modifier=Modifier) {
    val context=LocalContext.current
    OutlinedButton(onClick={val date=runCatching {LocalDate.parse(value)}.getOrDefault(LocalDate.now());DatePickerDialog(context,{_,y,m,d->onChange(LocalDate.of(y,m+1,d).toString())},date.year,date.monthValue-1,date.dayOfMonth).apply { datePicker.minDate=LocalDate.of(2000,1,1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();datePicker.maxDate=LocalDate.of(2100,12,31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.show()},modifier=modifier,contentPadding=PaddingValues(horizontal=9.dp,vertical=7.dp),shape=RoundedCornerShape(10.dp)) {Text("$label $value",fontSize=13.sp)}
}
@Composable private fun PeriodControls(period: String,setPeriod: (String)->Unit,start: String,setStart: (String)->Unit,end: String,setEnd: (String)->Unit) {
    ChoiceRow(listOf("today" to "今日","month" to "本月","year" to "本年","custom" to "自定义"),period,setPeriod)
    if(period=="custom") { Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {DateField("从",start,setStart,Modifier.weight(1f));DateField("到",end,setEnd,Modifier.weight(1f))};if(start>end)Text("结束日期不能早于开始日期",fontSize=12.sp,color=Expense) }
}
@Composable internal fun SearchField(value: String,onChange: (String)->Unit,label: String) {
    OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),placeholder={Text(label,fontSize=13.sp)},singleLine=true,textStyle=LocalTextStyle.current.copy(fontSize=14.sp),leadingIcon={Icon(Icons.Default.Search,null,Modifier.size(18.dp))},trailingIcon={if(value.isNotEmpty())IconButton(onClick={onChange("")}) {Icon(Icons.Default.Close,"清空搜索",Modifier.size(18.dp))}},shape=RoundedCornerShape(12.dp))
}
@Composable internal fun CategoryFilter(book: Book,value: String,setValue: (String)->Unit) {
    var open by remember {mutableStateOf(false)}
    val choices=listOf("all" to "全部分类","income" to "全部收入","fixed" to "固定收入","extra" to "额外收入")+book.expenseCategories.map { "expense:$it" to it }
    Box { OutlinedButton(onClick={open=true},Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)) {Text(choices.find {it.first==value}?.second?:"全部分类",Modifier.weight(1f),fontSize=14.sp);Icon(Icons.Default.KeyboardArrowDown,null,Modifier.size(18.dp))};DropdownMenu(open,{open=false},Modifier.heightIn(max=320.dp)) {choices.forEach { (key,label)->DropdownMenuItem(text={Text(label,fontSize=14.sp)},onClick={setValue(key);open=false}) }} }
}
private fun categoryMatches(e: Entry,value: String) = when(value) {"all"->true;"income"->e.type=="income";"fixed"->e.type=="income" && e.incomeKind!="extra";"extra"->e.type=="income" && e.incomeKind=="extra";else->e.type=="expense" && categoryName(e.category)==value.removePrefix("expense:")}

@Composable private fun SummaryPage(model: LedgerModel) {
    var period by rememberSaveable {mutableStateOf("today")};var start by rememberSaveable {mutableStateOf(today())};var end by rememberSaveable {mutableStateOf(today())}
    val entries=model.snapshot.book.activeEntries.filter {inPeriod(it.date,period,start,end)}
    val income=entries.filter {it.type=="income"}.sumOf {it.cents };val expense=entries.filter {it.type=="expense"}.sumOf {it.cents}
    val groups=entries.filter {it.type=="expense"}.groupBy {categoryName(it.category)}.entries.sortedByDescending {it.value.sumOf {e->e.cents}}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=12.dp)) {
        item {PeriodControls(period,{period=it},start,{start=it},end,{end=it})}
        item {Panel {Row(verticalAlignment=Alignment.CenterVertically) {Text(if(income>=expense)"结余" else "超支",Modifier.weight(1f));MoneyText(kotlin.math.abs(income-expense),Modifier.weight(2f),if(income>=expense)Income else Expense,"¥ ",29)};HorizontalDivider();Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {Column(Modifier.weight(1f)) {Text("收入",fontSize=12.sp);MoneyText(income,color=Income)};Column(Modifier.weight(1f)) {Text("支出",fontSize=12.sp);MoneyText(expense,color=Expense)}};Text("收入与开销，不包含借款",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)} }
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text("钱花在哪了",fontWeight=FontWeight.SemiBold);Text("共 ${entries.count {it.type=="expense"}} 笔",fontSize=13.sp)} }
        items(groups,key={it.key}) { (name,records) -> val cents=records.sumOf {it.cents};Panel {Row {Text("$name  ${records.size} 笔",Modifier.weight(1f),fontSize=15.sp);MoneyText(cents,Modifier.widthIn(max=150.dp))};LinearProgressIndicator(progress={if(expense>0)cents.toFloat()/expense else 0f},modifier=Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),color=Expense,trackColor=MaterialTheme.colorScheme.surfaceVariant)} }
        if(groups.isEmpty())item {Empty("这个时间范围内还没有支出记录")}
    }
}

@Composable private fun DetailsPage(model: LedgerModel) {
    var period by rememberSaveable {mutableStateOf("today")};var start by rememberSaveable {mutableStateOf(today())};var end by rememberSaveable {mutableStateOf(today())};var filter by rememberSaveable {mutableStateOf("all")};var query by rememberSaveable {mutableStateOf("")};var expanded by rememberSaveable {mutableStateOf(listOf<String>())}
    val book=model.snapshot.book;val groups=book.activeEntries.filter {inPeriod(it.date,period,start,end) && categoryMatches(it,filter) && matches(it,query)}.groupBy {it.date}.toSortedMap(compareByDescending {it})
    LazyColumn(verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=12.dp)) {
        item {PeriodControls(period,{period=it},start,{start=it},end,{end=it})};item {CategoryFilter(book,filter,{filter=it})};item {SearchField(query,{query=it},"搜索项目、姓名、备注或金额")}
        groups.forEach { (date,entries) ->
            item(key="day:$date") {Panel(Modifier.clickable {expanded=if(date in expanded)expanded-date else expanded+date}) {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1.15f)) {Text(if(date==today())"今日 · ${date.drop(5)}" else date,fontSize=15.sp,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold,maxLines=1);Text("共 ${entries.size} 笔",fontSize=11.sp)}
                Column(Modifier.weight(1f)) {Text("收入",fontSize=11.sp,color=Income);MoneyText(entries.filter {it.type=="income"}.sumOf {it.cents},color=Income,size=16)}
                Column(Modifier.weight(1f)) {Text("支出",fontSize=11.sp,color=Expense);MoneyText(entries.filter {it.type=="expense"}.sumOf {it.cents},color=Expense,size=16)}
                Icon(if(date in expanded)Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,"展开当日详情",Modifier.size(16.dp))
            }} }
            if(date in expanded)items(entries,key={it.id}) {EntryLine(it) {model.entry(it.category,it.type,it.incomeKind?:"fixed",it)}}
        }
        if(groups.isEmpty())item {Empty(if(query.isBlank())"这个时间范围内暂无记录" else "没有匹配的记录")}
    }
}
@Composable private fun EntryLine(entry: Entry,onClick: ()->Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick=onClick),shape=RoundedCornerShape(9.dp),color=MaterialTheme.colorScheme.surface) {Row(Modifier.padding(horizontal=11.dp,vertical=10.dp).heightIn(min=26.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {Text(entry.label,fontSize=14.sp,fontWeight=FontWeight.SemiBold);Text(entry.detail,Modifier.weight(1f),fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant);MoneyText(entry.cents,Modifier.widthIn(max=125.dp),if(entry.type=="income")Income else Expense,if(entry.type=="income")"+" else "−",16)} }
}
