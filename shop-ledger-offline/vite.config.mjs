import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('./web',import.meta.url));
export default defineConfig({root,base:'./',plugins:[react()],resolve:{alias:{'@':root}},build:{outDir:fileURLToPath(new URL('./build/web',import.meta.url)),emptyOutDir:true,target:'chrome90',cssTarget:'chrome90',rollupOptions:{output:{inlineDynamicImports:true}}}});
