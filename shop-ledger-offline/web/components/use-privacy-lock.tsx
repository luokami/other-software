'use client';
import {useCallback,useEffect,useRef,useState,type FormEvent} from 'react';
import {Dialog,DialogContent,DialogTitle,DialogDescription} from '@/components/ui/dialog';
import {createPrivacyConfig,parsePrivacyConfig,verifyPrivacyPin,type PrivacyConfig} from '@/lib/privacy';
import {toast} from 'sonner';

const KEY='ledger-privacy-lock',THROTTLE='ledger-lock-throttle';
type Mode='setup'|'unlock'|'disable'|null;
export function usePrivacyLock(onLock:()=>void){
 const [config,setConfig]=useState<PrivacyConfig|null>(null),[ready,setReady]=useState(false),[locked,setLocked]=useState(true),[invalid,setInvalid]=useState(false);
 const [mode,setMode]=useState<Mode>(null),[pin,setPin]=useState(''),[repeat,setRepeat]=useState(''),[error,setError]=useState(''),[working,setWorking]=useState(false),[blockedUntil,setBlockedUntil]=useState(0),[remaining,setRemaining]=useState(0);
 const status=useRef({config,ready,locked,invalid}),onLockRef=useRef(onLock),pending=useRef<(()=>void)|null>(null),throttle=useRef({count:0,until:0});
 const epoch=useRef(0);
 status.current={config,ready,locked,invalid};onLockRef.current=onLock;
 const canAccess=useCallback(()=>{const current=status.current;return current.ready&&!current.invalid&&(!current.config||!current.locked)},[]);
 const close=()=>{if(working)return;setMode(null);setPin('');setRepeat('');setError('');pending.current=null;};
 const lockNow=useCallback(()=>{
  epoch.current++;
  if(!status.current.config&&!status.current.invalid)return;
  status.current.locked=true;setLocked(true);setMode(null);setPin('');setRepeat('');setError('');pending.current=null;onLockRef.current();
 },[]);
 const run=useCallback((action:()=>void)=>{
  if(canAccess()){action();return true;}
  if(!status.current.ready){toast.info('正在读取隐私设置，请稍等');return false;}
  pending.current=action;setPin('');setRepeat('');setError('');setMode('unlock');return false;
 },[canAccess]);
 useEffect(()=>{
  try{const saved=parsePrivacyConfig(localStorage.getItem(KEY));status.current={config:saved,ready:true,locked:!!saved,invalid:false};setConfig(saved);setLocked(!!saved);
  }catch{status.current={config:null,ready:true,locked:true,invalid:true};setInvalid(true);setLocked(true);}
  try{const attempts=JSON.parse(localStorage.getItem(THROTTLE)||'null');if(attempts&&Number.isFinite(attempts.until)&&Number.isInteger(attempts.count)&&attempts.count>=0&&attempts.count<5){throttle.current=attempts;setBlockedUntil(attempts.until);}}catch{}
  setReady(true);
  const obscure=()=>{epoch.current++;if(status.current.config||status.current.invalid){document.documentElement.dataset.ledgerObscured='true';lockNow();}else{setMode(null);setPin('');setRepeat('');pending.current=null;}};
  const reveal=()=>requestAnimationFrame(()=>delete document.documentElement.dataset.ledgerObscured);
  const visibility=()=>{if(document.hidden)obscure();else reveal();};
  const storage=(event:StorageEvent)=>{if(event.key!==KEY)return;epoch.current++;try{const saved=parsePrivacyConfig(event.newValue);status.current.config=saved;status.current.invalid=false;setConfig(saved);setInvalid(false);if(saved)lockNow();else{status.current.locked=false;setLocked(false);}}catch{status.current.invalid=true;setInvalid(true);lockNow();}};
  window.addEventListener('ledger-native-lock',obscure);window.addEventListener('ledger-native-resume',reveal);document.addEventListener('visibilitychange',visibility);window.addEventListener('pagehide',obscure);window.addEventListener('pageshow',reveal);window.addEventListener('storage',storage);
  return()=>{window.removeEventListener('ledger-native-lock',obscure);window.removeEventListener('ledger-native-resume',reveal);document.removeEventListener('visibilitychange',visibility);window.removeEventListener('pagehide',obscure);window.removeEventListener('pageshow',reveal);window.removeEventListener('storage',storage);delete document.documentElement.dataset.ledgerObscured;};
 },[lockNow]);
 useEffect(()=>{if(blockedUntil<=Date.now()){setRemaining(0);return;}const update=()=>setRemaining(Math.max(0,Math.ceil((blockedUntil-Date.now())/1000)));update();const timer=setInterval(update,1000);return()=>clearInterval(timer);},[blockedUntil]);
 useEffect(()=>{
  if(!mode)return;
  const viewport=window.visualViewport,root=document.documentElement;
  let frame=0;
  const update=()=>{
   root.style.setProperty('--privacy-viewport-height',`${viewport?.height??window.innerHeight}px`);
   root.style.setProperty('--privacy-viewport-top',`${viewport?.offsetTop??0}px`);
   cancelAnimationFrame(frame);
   frame=requestAnimationFrame(()=>{
    const active=document.activeElement;
    if(!(active instanceof HTMLElement))return;
    const dialog=active.closest<HTMLElement>('.privacy-dialog');
    if(!dialog)return;
    const input=active.getBoundingClientRect(),visible=dialog.getBoundingClientRect();
    if(input.bottom>visible.bottom-12)dialog.scrollTop+=input.bottom-visible.bottom+12;
    else if(input.top<visible.top+12)dialog.scrollTop-=visible.top-input.top+12;
   });
  };
  update();viewport?.addEventListener('resize',update);viewport?.addEventListener('scroll',update);window.addEventListener('resize',update);document.addEventListener('focusin',update);
  return()=>{cancelAnimationFrame(frame);viewport?.removeEventListener('resize',update);viewport?.removeEventListener('scroll',update);window.removeEventListener('resize',update);document.removeEventListener('focusin',update);root.style.removeProperty('--privacy-viewport-height');root.style.removeProperty('--privacy-viewport-top');};
 },[mode]);
 function toggle(enabled:boolean){if(!canAccess()){run(()=>toggle(enabled));return;}setPin('');setRepeat('');setError('');pending.current=null;setMode(enabled?'setup':'disable');}
 async function submit(event:FormEvent){
  event.preventDefault();if(working||remaining>0)return;const startEpoch=epoch.current;setError('');setWorking(true);
  try{
   if(mode==='setup'){
    if(pin!==repeat)throw Error('两次密码不一致');const next=await createPrivacyConfig(pin);
    if(startEpoch!==epoch.current||document.hidden)return;
    localStorage.setItem(KEY,JSON.stringify(next));status.current.config=next;status.current.locked=false;setConfig(next);setLocked(false);toast.success('隐私锁已开启');
   }else{
    const current=status.current.config;if(!current||status.current.invalid)throw Error('隐私锁设置无法读取，请检查设备存储设置');
    const verified=await verifyPrivacyPin(pin,current);
    if(startEpoch!==epoch.current||document.hidden)return;
    if(!verified){
     const count=throttle.current.count+1,until=count>=5?Date.now()+30000:0;throttle.current={count:count>=5?0:count,until};try{localStorage.setItem(THROTTLE,JSON.stringify(throttle.current))}catch{}setBlockedUntil(until);setPin('');throw Error(until?'密码错误次数较多，请稍后再试':'密码不正确');
    }
    throttle.current={count:0,until:0};localStorage.removeItem(THROTTLE);setBlockedUntil(0);
    if(mode==='disable'){localStorage.removeItem(KEY);status.current.config=null;setConfig(null);toast.success('隐私锁已关闭');}
    status.current.locked=false;setLocked(false);
   }
   const action=pending.current;pending.current=null;setMode(null);setPin('');setRepeat('');action?.();
  }catch(e){setError((e as Error).message);}finally{setWorking(false);}
 }
 const dialog=<Dialog open={!!mode} onOpenChange={open=>{if(!open)close()}}><DialogContent className="privacy-dialog" showCloseButton={false} tabIndex={-1} onOpenAutoFocus={event=>event.preventDefault()}><DialogTitle>{mode==='setup'?'设置隐私密码':mode==='disable'?'关闭隐私锁':'解锁账本'}</DialogTitle><DialogDescription>{mode==='setup'?'此设备使用 6 位数字密码。请记住密码，密码不会随账本备份导出。':mode==='disable'?'输入当前密码后关闭隐私锁。':'锁定时仅可查看首页，输入密码后继续。'}</DialogDescription><form className="privacy-form" onSubmit={event=>void submit(event)}><label htmlFor="privacy-pin">{mode==='setup'?'设置密码':'密码'}<input id="privacy-pin" type="password" inputMode="numeric" autoComplete={mode==='setup'?'new-password':'current-password'} value={pin} maxLength={6} pattern="[0-9]{6}" required aria-label="6位数字密码" onChange={event=>setPin(event.target.value.replace(/\D/g,''))}/></label>{mode==='setup'&&<label htmlFor="privacy-repeat">再次输入<input id="privacy-repeat" type="password" inputMode="numeric" autoComplete="new-password" value={repeat} maxLength={6} pattern="[0-9]{6}" required onChange={event=>setRepeat(event.target.value.replace(/\D/g,''))}/></label>}{error&&<p className="form-error" role="alert">{error}</p>}{remaining>0&&<p className="helper">{remaining} 秒后可重试</p>}<div className="privacy-buttons"><button className="button secondary" type="button" disabled={working} onClick={close}>取消</button><button className="button primary" type="submit" disabled={working||remaining>0}>{working?'请稍等…':mode==='setup'?'开启':mode==='disable'?'确认关闭':'解锁'}</button></div></form></DialogContent></Dialog>;
 return {enabled:!!config||invalid,ready,locked:!ready||invalid||!!config&&locked,working,canAccess,run,lockNow,toggle,dialog,dismiss:()=>{if(!mode)return false;close();return true;}};
}
