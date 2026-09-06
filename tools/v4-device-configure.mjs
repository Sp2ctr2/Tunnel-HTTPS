import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const adb = '/home/sp2ctr2/.cache/latch-toolchain/android-sdk/platform-tools/adb';
const pkg = process.argv[2] || 'com.tunnelvpn.app.turbo';
const shadow = process.argv[3] !== 'off';
const battery = process.argv[4] === '4096';
const turbo = process.argv[5] === 'turbo';
const output = path.resolve(process.argv[6] || 'benchmark-results/v4-device/setup');
if (!['com.tunnelvpn.app.turbo', 'com.tunnelvpn.app'].includes(pkg)) throw new Error('Unexpected package');
fs.mkdirSync(output, { recursive: true });
const run = (...args) => execFileSync(adb, ['-s', 'emulator-5554', ...args], { encoding: 'utf8', timeout: 15000 });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
run('shell', 'am', 'force-stop', pkg);
run('root');
await delay(1500);
run('wait-for-device');
const user = run('shell', 'pm', 'list', 'packages', '-U', pkg).match(/uid:(\d+)/)?.[1];
if (!user) throw new Error('Missing app UID');
const prefs = `<?xml version="1.0" encoding="utf-8"?>\n<map>
<int name="vpn_disclosure_version" value="1" />
<boolean name="active" value="false" />
<boolean name="route_all" value="true" />
<boolean name="battery_saver" value="${battery}" />
<boolean name="doh_enabled" value="true" />
<boolean name="adblock_enabled" value="true" />
<boolean name="browser_traffic_only" value="false" />
<boolean name="turbo_mode_enabled" value="${turbo}" />
<boolean name="turbo_ai_enabled" value="true" />
<boolean name="aegis_v2_shadow_enabled" value="${shadow}" />
<int name="mtu" value="${battery ? 4096 : 32768}" />
<string name="dns_server">1.1.1.1</string>
</map>\n`;
const prefFile = path.join(output, 'test-only-preferences.xml');
fs.writeFileSync(prefFile, prefs);
const remoteDir = `/data/user/0/${pkg}/shared_prefs`;
run('shell', 'mkdir', '-p', remoteDir);
run('push', prefFile, `${remoteDir}/tunnel_vpn.xml`);
run('shell', 'chown', `${user}:${user}`, remoteDir, `${remoteDir}/tunnel_vpn.xml`);
run('shell', 'chmod', '660', `${remoteDir}/tunnel_vpn.xml`);
run('shell', 'appops', 'set', pkg, 'ACTIVATE_VPN', 'allow');
run('shell', 'pm', 'grant', pkg, 'android.permission.POST_NOTIFICATIONS');
run('shell', 'am', 'start', '-W', '-n', `${pkg}/com.tunnelvpn.app.MainActivity`);
await delay(2500);
const pid = run('shell', 'pidof', pkg).trim();
const result = { package: pkg, pid, shadow, battery, turbo, testOnlyPreauthorization: true, capturedAt: new Date().toISOString() };
if (pkg.endsWith('.turbo')) {
    run('forward', 'tcp:18333', `localabstract:webview_devtools_remote_${pid}`);
    const tabs = await (await fetch('http://127.0.0.1:18333/json')).json();
    const tab = tabs.find(value => value.url.includes('/assets/index.html'));
    if (!tab) throw new Error('WebView not ready');
    const ws = new WebSocket(tab.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
        const timer = setTimeout(() => { ws.close(); reject(new Error('CDP timeout')); }, 15000);
        ws.addEventListener('open', () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: {
            expression: 'TunnelAndroid.toggleVpn(true)', returnByValue: true
        } })));
        ws.addEventListener('message', event => {
            const message = JSON.parse(event.data);
            if (message.id !== 1) return;
            clearTimeout(timer);
            ws.close();
            if (message.error || message.result?.exceptionDetails) reject(new Error(JSON.stringify(message)));
            else resolve();
        });
        ws.addEventListener('error', () => { clearTimeout(timer); reject(new Error('CDP failed')); });
    });
} else {
    run('shell', 'am', 'start-foreground-service', '-n', `${pkg}/com.tunnelvpn.app.TunnelVpnService`, '-a', 'com.tunnelvpn.app.START');
}
const readyDeadline = Date.now() + 15000;
let vpnOwnerVerified = false;
while (Date.now() < readyDeadline) {
    const connectivity = run('shell', 'dumpsys', 'connectivity');
    if (connectivity.includes(`ni{VPN CONNECTED extra: VPN:${pkg}}`)) {
        vpnOwnerVerified = true;
        break;
    }
    await delay(500);
}
if (!vpnOwnerVerified) throw new Error('VPN did not become ready within the setup deadline');
result.vpnOwnerVerified = vpnOwnerVerified;
fs.writeFileSync(path.join(output, 'setup.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
