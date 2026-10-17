#!/usr/bin/env python3
"""Build the local-only React UI into a signed Android APK."""
import os,pathlib,re,subprocess,zipfile,shutil,urllib.request,secrets
ROOT=pathlib.Path(__file__).resolve().parent
os.chdir(ROOT)
BUILD=ROOT/'build';BUILD.mkdir(exist_ok=True)
def run(*args):subprocess.run([str(arg) for arg in args],check=True)

run('node','node_modules/vite/bin/vite.js','build')
web=BUILD/'web';html=(web/'index.html').read_text()
def script(match):
 content=(web/match.group(1).removeprefix('./')).read_text().replace('</script','<\\/script')
 return '<script type="module">'+content+'</script>'
def stylesheet(match):
 return '<style>'+(web/match.group(1).removeprefix('./')).read_text()+'</style>'
html=re.sub(r'<script[^>]*src="([^"]+)"[^>]*></script>',script,html)
html=re.sub(r'<link[^>]*rel="stylesheet"[^>]*href="([^"]+)"[^>]*>',stylesheet,html)
html=html.replace('<head>','<head><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\'; style-src \'unsafe-inline\'; img-src data:; font-src data:; connect-src \'none\'; base-uri \'none\'; form-action \'none\'">')
if re.search(r'<(?:script|link)[^>]*(?:src|href)=',html):raise RuntimeError('The offline HTML still contains external assets')
(ROOT/'app/src/main/assets/index.html').write_text(html)

shared=ROOT.parent/'shop-ledger'
tools=shared/'tools' if (shared/'tools/sdk/android-35/android.jar').exists() else ROOT/'tools'
tools.mkdir(exist_ok=True)
urls={'platform.zip':'https://dl.google.com/android/repository/platform-35_r02.zip','build-tools.zip':'https://dl.google.com/android/repository/build-tools_r35_linux.zip','ecj.jar':'https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.39.0/ecj-3.39.0.jar'}
for name,url in urls.items():
 p=tools/name
 if not p.exists():urllib.request.urlretrieve(url,p)
 if name.endswith('.zip') and not (tools/'sdk'/('android-35' if name=='platform.zip' else 'android-15')).exists():
  with zipfile.ZipFile(p) as z:z.extractall(tools/'sdk')
bt=tools/'sdk/android-15';platform=tools/'sdk/android-35/android.jar'
for name in ['aapt2','aapt','d8','zipalign','apksigner']:(bt/name).chmod(0o755)
for directory in ['classes','dex']:
 shutil.rmtree(BUILD/directory,ignore_errors=True);(BUILD/directory).mkdir()
run(bt/'aapt2','compile','--dir','app/src/main/res','-o',BUILD/'resources.zip')
run(bt/'aapt2','link','-o',BUILD/'unsigned.apk','-I',platform,'--manifest','app/src/main/AndroidManifest.xml','-A','app/src/main/assets',BUILD/'resources.zip')
run('java','-jar',tools/'ecj.jar','-1.8','-encoding','UTF-8','-bootclasspath',str(platform)+os.pathsep+str(bt/'core-lambda-stubs.jar'),'-d',BUILD/'classes','-warn:none','app/src/main/java/cn/shopledger/app/MainActivity.java')
with zipfile.ZipFile(BUILD/'classes.jar','w') as z:
 for p in (BUILD/'classes').rglob('*.class'):z.write(p,p.relative_to(BUILD/'classes'))
run(bt/'d8','--release','--min-api','26','--lib',platform,'--output',BUILD/'dex',BUILD/'classes.jar')
with zipfile.ZipFile(BUILD/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:z.write(BUILD/'dex/classes.dex','classes.dex')
run(bt/'zipalign','-f','-p','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
signing=shared/'signing' if (shared/'signing/shop-ledger.p12').exists() else ROOT/'signing';signing.mkdir(exist_ok=True)
password_file=signing/'password.txt';key=signing/'shop-ledger.p12'
if not password_file.exists():password_file.write_text(secrets.token_urlsafe(32));password_file.chmod(0o600)
os.environ['SHOP_LEDGER_SIGNING_PASSWORD']=password_file.read_text().strip()
if not key.exists():run('keytool','-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass:env','SHOP_LEDGER_SIGNING_PASSWORD','-alias','shop-ledger','-keyalg','RSA','-keysize','3072','-validity','10950','-dname','CN=Shop Ledger, OU=Personal App, O=Shop Ledger')
apk=BUILD/'xiaodian-ledger-offline-v1.1.0.apk'
run(bt/'apksigner','sign','--ks',key,'--ks-key-alias','shop-ledger','--ks-pass','env:SHOP_LEDGER_SIGNING_PASSWORD','--out',apk,BUILD/'aligned.apk')
run(bt/'apksigner','verify','--verbose',apk)
run(bt/'zipalign','-c','-p','4',apk)
run(bt/'aapt','dump','badging',apk)
print('Built:',apk)
