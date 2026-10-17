import {applyAction,emptyBook,type Action,type Book} from './lib/book.ts';
import {validateBook,makeBackup,type Preferences} from './lib/backup.ts';

type Snapshot={book:Book;revision:number};
export type NativeBridge={readBook:()=>string;saveBook:(book:string,revision:number,recovery:string)=>string;exportFile:(text:string,name:string,mime:string)=>string;chooseBackup:()=>string;readRecovery:()=>string};
declare global{interface Window{Native?:NativeBridge;ledgerHandleBack?:()=>boolean}}
function parsed(value:string){const data=JSON.parse(value);if(data.error)throw Error(data.error);return data;}
export function nativeFile(text:string,name:string,mime:string){const native=window.Native;if(!native)throw Error('文件操作需要安卓应用');parsed(native.exportFile(text,name,mime));}
export function chooseNativeBackup(){if(!window.Native)throw Error('请在安卓应用中选择备份');parsed(window.Native.chooseBackup());}
export function installOfflineApi(native:NativeBridge){
 let queue:Promise<unknown>=Promise.resolve();
 const snapshot=():Snapshot=>{const value=parsed(native.readBook());if(!Number.isSafeInteger(value.revision)||value.revision< -1)throw Error('本机账本版本无效');return {book:validateBook(value.book),revision:value.revision};};
 const response=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}});
 const preferences=():Preferences=>{const font=localStorage.getItem('ledger-font-scale');return {theme:localStorage.getItem('ledger-theme')==='dark'?'dark':'light',fontSize:(['tiny','small','standard','large','extra'].includes(font||'')?font:'standard') as Preferences['fontSize']};};
 window.fetch=async(input:RequestInfo|URL,init?:RequestInit)=>{
  const url=typeof input==='string'?input:input instanceof URL?input.href:input.url;
  if(new URL(url,location.href).pathname!=='/api/book')return response({error:'离线版不连接网络'},403);
  const execute=()=>{
   try{
    const current=snapshot(),method=init?.method||'GET';
    if(method==='GET')return response(current);
    if(typeof init?.body!=='string')return response({error:'输入内容无效'},400);
    const body=JSON.parse(init.body);
    let next:Book,recovery='';
    if(method==='POST')next=validateBook(applyAction(current.book,body as Action));
    else if(method==='PUT'){
     if(body.expectedRevision!==current.revision)return response({error:'账本已更新，请重新选择备份'},409);
     next=validateBook(body.book);recovery=JSON.stringify(makeBackup(current.book,preferences()));
    }else return response({error:'不支持的操作'},405);
    const saved=parsed(native.saveBook(JSON.stringify(next),current.revision,recovery));
    return response({book:next,revision:saved.revision});
   }catch(e){return response({error:(e as Error).message||'本机保存失败'},400);}
  };
  const result=queue.then(execute);queue=result.catch(()=>{});return result;
 };
}

export function installNativeLifecycle(){
 window.addEventListener('ledger-file-result',event=>{
  const message=(event as CustomEvent<{message:string}>).detail?.message;
  if(message)void import('sonner').then(({toast})=>toast.info(message));
 });
}
