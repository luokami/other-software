import type {Backup} from './backup.ts';
export type AutoBackupRecord={day:string;hash:string;backup:Backup};
const STORE='snapshots';
export function planAutoBackups(existing:AutoBackupRecord[],incoming:AutoBackupRecord){
 const previous=[...existing].sort((a,b)=>b.day.localeCompare(a.day));
 if(!existing.some(record=>record.day===incoming.day)&&previous[0]?.hash===incoming.hash){const records=previous.slice(0,7);return {records,changed:false,latest:records[0]};}
 const same=existing.find(record=>record.day===incoming.day&&record.hash===incoming.hash);
 const next=[...existing.filter(record=>record.day!==incoming.day),same||incoming].sort((a,b)=>b.day.localeCompare(a.day)).slice(0,7);
 return {records:next,changed:!same,latest:next[0]};
}
function openDatabase(){return new Promise<IDBDatabase>((resolve,reject)=>{
 if(typeof indexedDB==='undefined'){reject(Error('当前设备无法保存自动备份'));return;}
 const request=indexedDB.open('xiaodian-ledger-auto-backups',1);
 let rejected=false;
 request.onupgradeneeded=()=>request.result.createObjectStore(STORE,{keyPath:'day'});
 request.onsuccess=()=>{if(rejected)request.result.close();else resolve(request.result);};
 request.onerror=()=>reject(Error('无法打开自动备份存储'));
 request.onblocked=()=>{rejected=true;reject(Error('请关闭其他账本页面后重试自动备份'));};
 });}
export async function listAutoBackups(){
 const database=await openDatabase();
 try{return await new Promise<AutoBackupRecord[]>((resolve,reject)=>{
  const transaction=database.transaction(STORE,'readonly'),request=transaction.objectStore(STORE).getAll();
  transaction.oncomplete=()=>resolve((request.result as AutoBackupRecord[]).sort((a,b)=>b.day.localeCompare(a.day)));
  transaction.onabort=()=>reject(Error('自动备份读取失败'));
 });}finally{database.close();}
}
export async function saveAutoBackup(backup:Backup,day:string){
 const bytes=new TextEncoder().encode(JSON.stringify({book:backup.book,preferences:backup.preferences}));
 const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),b=>b.toString(16).padStart(2,'0')).join('');
 const database=await openDatabase();
 try{return await new Promise<ReturnType<typeof planAutoBackups>>((resolve,reject)=>{
  const transaction=database.transaction(STORE,'readwrite'),store=transaction.objectStore(STORE),request=store.getAll();
  let result:ReturnType<typeof planAutoBackups>;
  request.onsuccess=()=>{
   result=planAutoBackups(request.result as AutoBackupRecord[],{day,hash,backup});
   const keep=new Set(result.records.map(record=>record.day));
   if(result.changed&&keep.has(day))store.put({day,hash,backup});
   for(const record of request.result as AutoBackupRecord[])if(!keep.has(record.day))store.delete(record.day);
  };
  transaction.oncomplete=()=>resolve(result);
  transaction.onabort=()=>reject(Error('自动备份未保存，可能是设备存储空间不足'));
 });}finally{database.close();}
}
