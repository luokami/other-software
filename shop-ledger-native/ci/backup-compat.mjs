import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
import {parseBackup} from '../../shop-ledger-offline/web/lib/backup.ts';
const exported=readFileSync('app/build/compat-backup.json','utf8');
const native=JSON.parse(exported),legacy=parseBackup(exported);
assert.deepEqual(legacy.book,native.book);
assert.deepEqual(legacy.preferences,native.preferences);
console.log('PASS: native JSON backup accepted by the original web validator without losing fields.');
