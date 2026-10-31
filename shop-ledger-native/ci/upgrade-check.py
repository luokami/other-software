"""Release-to-release upgrade smoke test on an isolated, rooted CI emulator.
All records are synthetic. Never run on a personal device.
"""
import json, pathlib, sqlite3, subprocess, time

ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'app/build/outputs/upgrade-check'
OUT.mkdir(parents=True,exist_ok=True)
PKG='cn.shopledger.offline'
BASE=f'/data/user/0/{PKG}'
def adb(*args):
    return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT)

def pull_database(name):
    target=OUT/name
    for suffix in ('','-wal','-shm'):
        try: adb('pull',f'{BASE}/databases/{name}{suffix}',str(target)+suffix)
        except subprocess.CalledProcessError:
            if not suffix: raise
    return target

# A debuggable instrumentation build must not mask a signing mismatch.
try:adb('uninstall',PKG)
except subprocess.CalledProcessError:pass
adb('install',str(ROOT.parent/'shop-ledger-offline/releases/xiaodian-ledger-offline-v1.2.0.apk'))
adb('shell','am','start','-W','-n',f'{PKG}/cn.shopledger.app.MainActivity')
time.sleep(3)
adb('shell','am','force-stop',PKG)
adb('root');adb('wait-for-device')
uid=adb('shell','stat','-c','%u',BASE).strip()
fixture=json.loads((ROOT/'app/build/compat-backup.json').read_text())
fixture['book']['name']='覆盖升级测试店铺'
source=OUT/'ledger.db'
if source.exists():source.unlink()
with sqlite3.connect(source) as db:
    db.execute('CREATE TABLE books(id INTEGER PRIMARY KEY,content TEXT NOT NULL,revision INTEGER NOT NULL,recovery TEXT NOT NULL DEFAULT \'\')')
    db.execute('INSERT INTO books VALUES(1,?,?,?)',(json.dumps(fixture['book'],ensure_ascii=False),88,json.dumps(fixture,ensure_ascii=False)))
    db.execute('PRAGMA user_version=2')
adb('shell','mkdir','-p',f'{BASE}/databases')
for suffix in ('-wal','-shm'):
    adb('shell','rm','-f',f'{BASE}/databases/ledger.db{suffix}')
adb('push',str(source),f'{BASE}/databases/ledger.db')
adb('shell','chown',f'{uid}:{uid}',f'{BASE}/databases/ledger.db')
adb('shell','restorecon','-R',f'{BASE}/databases')
result=adb('install','-r',str(ROOT/'releases/xiaodian-ledger-native-v1.3.0.apk'))
assert 'Success' in result,result
launcher=adb('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER',PKG)
assert 'cn.shopledger.app.MainActivity' in launcher,launcher
adb('shell','am','start','-W','-n',f'{PKG}/cn.shopledger.app.MainActivity')
for _ in range(40):
    try:
        native=pull_database('ledger-native.db')
        with sqlite3.connect(native) as db:
            row=db.execute('SELECT json,revision FROM ledger_state WHERE id=1').fetchone()
            recovery=db.execute('SELECT json FROM recovery_state WHERE id=1').fetchone()
        if row:break
    except (subprocess.CalledProcessError,sqlite3.Error):pass
    time.sleep(1)
else:raise AssertionError('Native migration did not commit')
assert json.loads(row[0])==fixture['book']
assert row[1]==88
assert json.loads(recovery[0])['book']==fixture['book']
legacy=pull_database('ledger.db')
with sqlite3.connect(legacy) as db:
    assert json.loads(db.execute('SELECT content FROM books WHERE id=1').fetchone()[0])==fixture['book']
package=adb('shell','dumpsys','package',PKG)
assert 'versionCode=33 ' in package
(OUT/'result.txt').write_text('PASS: signed v1.2.0 -> signed v1.3.0 in-place update; complete book, revision, pre-restore backup and untouched old database verified.\n')
print((OUT/'result.txt').read_text())
