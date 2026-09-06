import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const output = path.resolve(process.argv[2] || 'benchmark-results/v4-delivery-device/b5-network');
fs.mkdirSync(output, { recursive: true });
const adb = '/home/sp2ctr2/.cache/latch-toolchain/android-sdk/platform-tools/adb';
const snapshot = () => JSON.parse(execFileSync('node', ['tools/webview_eval.mjs', '({state:JSON.parse(TunnelAndroid.getCurrentStateSnapshot()),traffic:JSON.parse(TunnelAndroid.getTrafficStats()),ai:JSON.parse(TunnelAndroid.getTurboAiStatus())})'], { encoding: 'utf8', timeout: 20000 })).result.value;
const wifi = value => execFileSync(adb, ['-s', 'emulator-5554', 'shell', 'svc', 'wifi', value], { encoding: 'utf8', timeout: 10000 });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const before = snapshot();
let disabled;
try {
    wifi('disable');
    await delay(2000);
    disabled = snapshot();
} finally {
    wifi('enable');
}
await delay(6000);
const after = snapshot();
const count = value => value.traffic.diagnostics.networkChangeResets;
const result = { scope: 'emulator Wi-Fi toggle, not carrier handoff', capturedAt: new Date().toISOString(), before, disabled, after,
    pass: after.state.state === 'connected' && count(after) > count(before) };
fs.writeFileSync(path.join(output, 'transition.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({ pass: result.pass, beforeResets: count(before), afterResets: count(after), serviceGeneration: after.state.generation }));
if (!result.pass) process.exitCode = 1;
