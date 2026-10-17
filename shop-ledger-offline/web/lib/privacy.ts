export type PrivacyConfig={version:1;salt:string;verifier:string;iterations:200000};
const hex=(bytes:Uint8Array)=>Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
function saltBytes(value:string){const bytes=new Uint8Array(16);for(let i=0;i<16;i++)bytes[i]=parseInt(value.slice(i*2,i*2+2),16);return bytes;}
async function derive(pin:string,salt:Uint8Array<ArrayBuffer>){
 const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(pin),'PBKDF2',false,['deriveBits']);
 return hex(new Uint8Array(await crypto.subtle.deriveBits({name:'PBKDF2',salt,iterations:200000,hash:'SHA-256'},key,256)));
}
export function parsePrivacyConfig(value:string|null):PrivacyConfig|null{
 if(value===null)return null;
 const config=JSON.parse(value) as PrivacyConfig;
 if(!config||config.version!==1||config.iterations!==200000||!/^[a-f0-9]{32}$/.test(config.salt)||!/^[a-f0-9]{64}$/.test(config.verifier))throw Error('隐私锁设置无法读取');
 return {version:1,salt:config.salt,verifier:config.verifier,iterations:200000};
}
export async function createPrivacyConfig(pin:string):Promise<PrivacyConfig>{
 if(!/^\d{6}$/.test(pin))throw Error('请设置 6 位数字密码');
 const salt=crypto.getRandomValues(new Uint8Array(16));
 return {version:1,salt:hex(salt),verifier:await derive(pin,salt),iterations:200000};
}
export async function verifyPrivacyPin(pin:string,config:PrivacyConfig){
 if(!/^\d{6}$/.test(pin))return false;
 const result=await derive(pin,saltBytes(config.salt));let difference=0;
 for(let i=0;i<result.length;i++)difference|=result.charCodeAt(i)^config.verifier.charCodeAt(i);
 return difference===0;
}
