const defaultCategories=['房租','蔬菜','米面','调料','肉类','礼品','工人工资','生活缴费','水电开销','其他杂项'];
export type Entry={id:string;type:'income'|'expense';category:string;cents:number;date:string;note:string;incomeKind?:'fixed'|'extra';item?:string;deletedAt?:string;reminderRef?:string};
export type Payment={id:string;cents:number;date:string;deletedAt?:string};
export type Loan={id:string;name:string;cents:number;date:string;note:string;payments:Payment[];deletedAt?:string};
export type Reminder={id:string;name:string;kind:'房租'|'工人工资';cents:number;day:number;deletedAt?:string};
export type Book={name:string;entries:Entry[];loans:Loan[];reminders:Reminder[];categories?:string[]};
export const emptyBook=():Book=>({name:'我的店铺',entries:[],loans:[],reminders:[],categories:[]});
export function localDate(){const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;}
export function parseMoney(value:string){if(!/^\d{1,8}(\.\d{1,2})?$/.test(value.trim()))throw Error('金额最多两位小数');const [whole,part='']=value.trim().split('.');const c=Number(whole)*100+Number(part.padEnd(2,'0'));if(c<1||c>9999999999)throw Error('金额需大于 0，且不超过 99,999,999.99 元');return c;}
export const formatMoney=(value:number)=>(value/100).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
export const repaid=(loan:Loan)=>loan.payments.filter(p=>!p.deletedAt).reduce((sum,p)=>sum+p.cents,0);
export function dueReminders(b:Book,date:string){const month=date.slice(0,7),day=Number(date.slice(8));return b.reminders.filter(r=>!r.deletedAt&&day>=Math.min(r.day,new Date(Number(date.slice(0,4)),Number(date.slice(5,7)),0).getDate())&&!b.entries.some(e=>!e.deletedAt&&e.reminderRef===`${r.id}:${month}`));}
export function loanSummary(b:Book,date:string){const loans=b.loans.filter(l=>!l.deletedAt);return {outstanding:loans.reduce((a,l)=>a+l.cents-repaid(l),0),received:loans.reduce((a,l)=>a+l.payments.filter(p=>!p.deletedAt&&p.date===date).reduce((n,p)=>n+p.cents,0),0),settled:loans.filter(l=>repaid(l)===l.cents).reduce((a,l)=>a+l.payments.filter(p=>!p.deletedAt&&p.date===date).reduce((n,p)=>n+p.cents,0),0)};}
function text(v:unknown,label:string,max=100){if(typeof v!=='string'||!v.trim()||v.trim().length>max)throw Error(`${label}不能为空，最多 ${max} 个字`);return v.trim();}
function id(v:unknown){const s=text(v,'记录编号',80);if(!/^[a-zA-Z0-9:-]+$/.test(s))throw Error('记录编号无效');return s;}
function amount(v:unknown){if(typeof v!=='number'||!Number.isSafeInteger(v)||v<1||v>9999999999)throw Error('金额无效');return v;}
function date(v:unknown){const s=text(v,'日期',10);if(!/^\d{4}-\d{2}-\d{2}$/.test(s)||s<'2000-01-01'||s>'2100-12-31'||new Date(s+'T12:00:00Z').toISOString().slice(0,10)!==s)throw Error('日期无效');return s;}
function note(v:unknown){if(v==null)return '';if(typeof v!=='string'||v.length>300)throw Error('备注最多 300 个字');return v.trim();}
export type Action={op:string;[key:string]:unknown};
export function applyAction(previous:Book,a:Action):Book{
 const b=structuredClone(previous),now=new Date().toISOString();const target=id(a.id??'none');
 if(a.op==='saveEntry'){if(a.type!=='income'&&a.type!=='expense')throw Error('请选择收入或支出');const existing=b.entries.find(e=>e.id===target);const incomeKind=a.incomeKind??(existing?.type==='income'?existing.incomeKind:undefined)??'fixed';if(a.type==='income'&&incomeKind!=='fixed'&&incomeKind!=='extra')throw Error('请选择固定收入或额外收入');const e:Entry={id:target,type:a.type,category:text(a.category,a.type==='income'?(incomeKind==='extra'?'是什么收入':'姓名'):'项目',30),cents:amount(a.cents),date:date(a.date),note:note(a.note)};if(a.type==='income')e.incomeKind=incomeKind as 'fixed'|'extra';if(a.type==='expense'&&e.category==='其他杂项')e.item=text(a.item,'具体用途',30);else if(a.type==='expense'&&e.category==='工人工资')e.item=text(a.item,'姓名',30);else if(a.type==='expense'&&typeof a.item==='string'&&a.item.trim())e.item=text(a.item,'具体用途',30);if(a.type==='expense'&&['蔬菜','米面','调料','肉类','生活缴费'].includes(e.category))e.note=text(a.note,'具体是什么',300);if(existing){if(existing.reminderRef&&a.type==='income')throw Error('工资和房租提醒只能记为开销');if(existing.reminderRef) e.reminderRef=existing.reminderRef;b.entries[b.entries.indexOf(existing)]=e;}else{if(b.entries.length>=10000)throw Error('账目已达到 10,000 笔');b.entries.unshift(e);}}
 else if(a.op==='deleteEntry'||a.op==='restoreEntry'){const e=b.entries.find(x=>x.id===target);if(!e)throw Error('找不到这笔账');if(a.op==='deleteEntry')e.deletedAt=now;else {if(e.deletedAt&&Date.now()-Date.parse(e.deletedAt)>30*86400000)throw Error('已超过 30 天恢复期限');delete e.deletedAt;}}
 else if(a.op==='saveLoan'){const existing=b.loans.find(x=>x.id===target),cents=amount(a.cents);if(existing&&existing.payments.some(p=>!p.deletedAt&&p.date<date(a.date)))throw Error('借出日期不能晚于已有的还钱日期');if(existing&&cents<repaid(existing))throw Error('借款金额不能小于已经归还的金额');const l:Loan={id:target,name:text(a.name,'姓名',30),cents,date:date(a.date),note:note(a.note),payments:existing?.payments??[]};if(existing)b.loans[b.loans.indexOf(existing)]=l;else{if(b.loans.length>=1000)throw Error('借款记录已达到 1,000 笔');b.loans.unshift(l);}}
 else if(a.op==='repay'){const l=b.loans.find(x=>x.id===id(a.loanId)&&!x.deletedAt);if(!l)throw Error('找不到这笔借款');const cents=amount(a.cents),when=date(a.date);if(when<l.date)throw Error('还钱日期不能早于借出日期');const existed=l.payments.find(p=>p.id===target);if(existed){if(existed.cents!==cents||existed.date!==when||existed.deletedAt)throw Error('该还款编号已使用');return b;}if(cents>l.cents-repaid(l))throw Error('还钱金额超过了剩余欠款');if(l.payments.length>=1000)throw Error('这笔借款的还钱记录过多');l.payments.push({id:target,cents,date:when});}
 else if(a.op==='undoPayment'){const l=b.loans.find(x=>x.id===id(a.loanId)),p=l?.payments.find(x=>x.id===target);if(!p)throw Error('找不到还钱记录');p.deletedAt=now;}
 else if(a.op==='deleteLoan'||a.op==='restoreLoan'){const l=b.loans.find(x=>x.id===target);if(!l)throw Error('找不到借款');if(a.op==='deleteLoan')l.deletedAt=now;else delete l.deletedAt;}
 else if(a.op==='addCategory'){const name=text(a.name,'分类名称',16);const categories=b.categories??[];if(defaultCategories.includes(name)||categories.includes(name))throw Error('已经有这个分类了');if(categories.length>=30)throw Error('自定义分类最多 30 个');b.categories=[...categories,name];}
 else if(a.op==='removeCategory'){const name=text(a.name,'分类名称',16);if(defaultCategories.includes(name))throw Error('默认分类不能删除');b.categories=(b.categories??[]).filter(c=>c!==name);}
 else if(a.op==='saveName')b.name=text(a.name,'店铺名称',30);
 else if(a.op==='saveReminder'){if(a.kind!=='房租'&&a.kind!=='工人工资')throw Error('提醒类型无效');const day=Number(a.day);if(!Number.isInteger(day)||day<1||day>31)throw Error('日期需为每月 1～31 日');const r:Reminder={id:target,name:text(a.name,'提醒名称',30),kind:a.kind,cents:amount(a.cents),day};const old=b.reminders.find(x=>x.id===target);if(old)b.reminders[b.reminders.indexOf(old)]=r;else{if(b.reminders.length>=100)throw Error('提醒最多 100 条');b.reminders.push(r);}}
 else if(a.op==='deleteReminder'){const r=b.reminders.find(x=>x.id===target);if(!r)throw Error('找不到提醒');r.deletedAt=now;}
 else if(a.op==='confirmReminder'){const r=b.reminders.find(x=>x.id===target&&!x.deletedAt);if(!r)throw Error('找不到提醒');const when=date(a.date),ref=`${r.id}:${when.slice(0,7)}`;if(b.entries.some(e=>!e.deletedAt&&e.reminderRef===ref))return b;b.entries.unshift({id:id(a.entryId),type:'expense',category:r.kind,cents:r.cents,date:when,note:r.name,reminderRef:ref});}
 else throw Error('不支持的操作');
 return b;
}

export type Period='today'|'month'|'year'|'custom';
export function recycleRecords(book:Book,now=Date.now()){
 const newestFirst=(a:{deletedAt?:string},b:{deletedAt?:string})=>Date.parse(b.deletedAt!)-Date.parse(a.deletedAt!);
 return {
  entries:book.entries.filter(e=>e.deletedAt&&now-Date.parse(e.deletedAt)<=30*86400000).sort(newestFirst),
  loans:book.loans.filter(l=>l.deletedAt).sort(newestFirst)
 };
}
export function groupEntriesByDay(entries:readonly Entry[]){
 const days=new Map<string,{date:string;entries:Entry[];income:number;expense:number}>();
 for(const entry of entries){
  if(entry.deletedAt)continue;
  let day=days.get(entry.date);
  if(!day){day={date:entry.date,entries:[],income:0,expense:0};days.set(entry.date,day);}
  day.entries.push(entry);day[entry.type]+=entry.cents;
 }
 return [...days.values()].sort((a,b)=>b.date.localeCompare(a.date));
}
export function filterEntriesByPeriod(entries:readonly Entry[],period:Period,today:string,start:string,end:string):Entry[]{
 if(period==='custom'&&(!start||!end||start>end))return [];
 return entries.filter(e=>!e.deletedAt&&(period==='today'?e.date===today:period==='month'?e.date.startsWith(today.slice(0,7)):period==='year'?e.date.startsWith(today.slice(0,4)):e.date>=start&&e.date<=end)).sort((a,b)=>b.date.localeCompare(a.date));
}

export function frequentExpenseItems(entries:readonly Entry[],category:string,limit=12){
 const normalize=(value:string)=>value==='水电开销'?'生活缴费':value;
 const current=normalize(category),field=current==='工人工资'||current==='其他杂项'?'item':'note';
 const counts=new Map<string,{value:string;count:number;lastDate:string}>();
 for(const entry of entries){
  if(entry.deletedAt||entry.type!=='expense'||normalize(entry.category)!==current)continue;
  const value=(entry[field]||'').trim();
  if(!value)continue;
  const previous=counts.get(value);
  counts.set(value,{value,count:(previous?.count||0)+1,lastDate:previous&&previous.lastDate>entry.date?previous.lastDate:entry.date});
 }
 return {field,items:[...counts.values()].sort((a,b)=>b.count-a.count||b.lastDate.localeCompare(a.lastDate)||a.value.localeCompare(b.value,'zh-CN')).slice(0,Math.max(0,limit)).map(({value,count})=>({value,count}))};
}
