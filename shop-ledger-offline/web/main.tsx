import React from 'react';
import {createRoot} from 'react-dom/client';
import App from './App';
import './globals.css';
import {installOfflineApi,installNativeLifecycle} from './native';

if(!globalThis.structuredClone)globalThis.structuredClone=value=>JSON.parse(JSON.stringify(value));
if(!crypto.randomUUID)crypto.randomUUID=()=>{const b=crypto.getRandomValues(new Uint8Array(16));b[6]=(b[6]&15)|64;b[8]=(b[8]&63)|128;const h=Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');return `${h.slice(0,8)}-${h.slice(8,12)}-${h.slice(12,16)}-${h.slice(16,20)}-${h.slice(20)}` as `${string}-${string}-${string}-${string}-${string}`;};
if(!window.Native)throw Error('请安装离线版 APK 后使用');
installOfflineApi(window.Native);installNativeLifecycle();
createRoot(document.getElementById('root')!).render(<App/>);
