import {repaid,type Book,type Entry,type Loan,type Payment,type Reminder} from './book.ts';

export const MAX_BACKUP_BYTES=2_000_000;
export type Preferences={theme:'light'|'dark';fontSize:'tiny'|'small'|'standard'|'large'|'extra'};
export type Backup={format:'xiaodian-ledger-backup';version:1;createdAt:string;book:Book;preferences:Preferences};
const fonts=['tiny','small','standard','large','extra'];
function fail(message='备份内容不完整或格式不正确'):never{throw Error(message);}
function object(value:unknown):Record<string,unknown>{if(!value||typeof value!=='object'||Array.isArray(value))fail();return value as Record<string,unknown>;}
function text(value:unknown,max:number,empty=false){if(typeof value!=='string'||value.length>max||!empty&&!value.trim())fail();return value;}
function identifier(value:unknown){const result=text(value,80);if(!/^[a-zA-Z0-9:-]+$/.test(result))fail('备份中存在无效的记录编号');return result;}
function money(value:unknown){if(typeof value!=='number'||!Number.isSafeInteger(value)||value<1||value>9999999999)fail('备份中存在无效的金额');return value;}
function day(value:unknown){const result=text(value,10);if(!/^\d{4}-\d{2}-\d{2}$/.test(result)||result<'2000-01-01'||result>'2100-12-31')fail('备份中存在无效的日期');const date=new Date(result+'T12:00:00Z');if(!Number.isFinite(date.getTime())||date.toISOString().slice(0,10)!==result)fail('备份中存在无效的日期');return result;}
function timestamp(value:unknown){const result=text(value,40);if(!/^\d{4}-\d{2}-\d{2}T/.test(result)||!Number.isFinite(Date.parse(result)))fail('备份时间格式不正确');return result;}
function deleted(value:Record<string,unknown>){return value.deletedAt===undefined?{}:{deletedAt:timestamp(value.deletedAt)};}
function collection<T extends {id:string}>(value:unknown,max:number,parse:(value:Record<string,unknown>)=>T){if(!Array.isArray(value)||value.length>max)fail('备份记录数量超过支持范围');const records=value.map(item=>parse(object(item)));if(new Set(records.map(item=>item.id)).size!==records.length)fail('备份中存在重复的记录编号');return records;}

// Whitelist every field instead of trusting or merging imported objects.
export function validateBook(value:unknown):Book{
 const input=object(value);
 const entries=collection<Entry>(input.entries,10000,e=>{
  if(e.type!=='income'&&e.type!=='expense')fail('备份中的收支类型无效');
  const result:Entry={id:identifier(e.id),type:e.type,category:text(e.category,30),cents:money(e.cents),date:day(e.date),note:text(e.note,300,true),...deleted(e)};
  if(e.incomeKind!==undefined){if(e.incomeKind!=='fixed'&&e.incomeKind!=='extra')fail('备份中的收入类型无效');if(e.type==='income')result.incomeKind=e.incomeKind;}
  if(e.item!==undefined)result.item=text(e.item,30,true);
  if(e.reminderRef!==undefined)result.reminderRef=text(e.reminderRef,100);
  return result;
 });
 const loans=collection<Loan>(input.loans,1000,l=>{
  const result:Loan={id:identifier(l.id),name:text(l.name,30),cents:money(l.cents),date:day(l.date),note:text(l.note,300,true),payments:collection<Payment>(l.payments,1000,p=>({id:identifier(p.id),cents:money(p.cents),date:day(p.date),...deleted(p)})),...deleted(l)};
  if(repaid(result)>result.cents||result.payments.some(p=>!p.deletedAt&&p.date<result.date))fail('备份中的还款记录与借款不一致');
  return result;
 });
 const reminders=collection<Reminder>(input.reminders,100,r=>{
  if(r.kind!=='房租'&&r.kind!=='工人工资')fail('备份中的提醒类型无效');
  if(typeof r.day!=='number'||!Number.isInteger(r.day)||r.day<1||r.day>31)fail('备份中的提醒日期无效');
  return {id:identifier(r.id),name:text(r.name,30),kind:r.kind,cents:money(r.cents),day:r.day,...deleted(r)};
 });
 const categories=input.categories??[];
 if(!Array.isArray(categories)||categories.length>30)fail('备份中的分类数量无效');
 const names=categories.map(name=>text(name,16));
 if(new Set(names).size!==names.length)fail('备份中存在重复分类');
 const result={name:text(input.name,30),entries,loans,reminders,categories:names};
 if(new TextEncoder().encode(JSON.stringify(result)).length>1_500_000)fail('备份超过账本容量');
 return result;
}

export function makeBackup(book:Book,preferences:Preferences,createdAt=new Date().toISOString()):Backup{
 return {format:'xiaodian-ledger-backup',version:1,createdAt,book:structuredClone(book),preferences:{...preferences}};
}
export function parseBackup(content:string):Backup{
 if(new TextEncoder().encode(content).length>MAX_BACKUP_BYTES)fail('备份文件过大，最多支持 2 MB');
 let raw:unknown;try{raw=JSON.parse(content);}catch{fail('请选择小店账本生成的 JSON 备份文件');}
 const input=object(raw);
 if(input.format!=='xiaodian-ledger-backup')fail('这不是小店账本的备份文件');
 if(input.version!==1)fail('暂不支持这个版本的备份文件');
 const preferences=object(input.preferences);
 if(preferences.theme!=='light'&&preferences.theme!=='dark'||!fonts.includes(String(preferences.fontSize)))fail('备份中的显示设置无效');
 return {format:'xiaodian-ledger-backup',version:1,createdAt:timestamp(input.createdAt),book:validateBook(input.book),preferences:{theme:preferences.theme as Preferences['theme'],fontSize:preferences.fontSize as Preferences['fontSize']}};
}

// Spreadsheet formula prefixes are escaped, including those after whitespace.
function csvCell(value:string){const safe=/^\s*[=+\-@\t\r]/.test(value)?"'"+value:value;return '"'+safe.replaceAll('"','""')+'"';}
function csv(rows:string[][]){return '\uFEFF'+rows.map(row=>row.map(csvCell).join(',')).join('\r\n')+'\r\n';}
const decimal=(cents:number)=>(cents/100).toFixed(2);
export function entriesCsv(book:Book){
 return csv([['日期','收支类型','分类','项目或姓名','金额（元）','备注'],...book.entries.filter(e=>!e.deletedAt).sort((a,b)=>b.date.localeCompare(a.date)).map(e=>[e.date,e.type==='income'?'收入':'支出',e.type==='income'?(e.incomeKind==='extra'?'额外收入':'固定收入'):e.category==='水电开销'?'生活缴费':e.category,e.type==='income'?e.category:e.item||'',decimal(e.cents),e.note])]);
}
export function loansCsv(book:Book){
 return csv([['姓名','借出日期','借出金额（元）','已还金额（元）','剩余欠款（元）','是否结清','备注','还款明细','借款编号'],...book.loans.filter(l=>!l.deletedAt).sort((a,b)=>b.date.localeCompare(a.date)).map(l=>{const paid=repaid(l);return [l.name,l.date,decimal(l.cents),decimal(paid),decimal(l.cents-paid),paid===l.cents?'已结清':'未结清',l.note,l.payments.filter(p=>!p.deletedAt).map(p=>`${p.date}：${decimal(p.cents)}元`).join('；'),l.id];})]);
}
