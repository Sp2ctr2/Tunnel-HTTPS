import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const adb = '/home/sp2ctr2/.cache/latch-toolchain/android-sdk/platform-tools/adb';
const kind = process.argv[2] || 'matrix';
const output = path.resolve(process.argv[3] || 'benchmark-results/v4-device/probes');
const pkg = process.argv[4] || 'com.tunnelvpn.app.turbo';
const tlsSamples = Number(process.argv[5] || 5);
if (!Number.isInteger(tlsSamples) || tlsSamples < 1 || tlsSamples > 30) throw new Error('TLS samples must be 1..30');
fs.mkdirSync(output, { recursive: true });
const run = (...args) => execFileSync(adb, ['-s', 'emulator-5554', ...args], { encoding: 'utf8', timeout: 20000, maxBuffer: 16 * 1024 * 1024 });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const cases = kind === 'tls' ? [
    ['tls-head', ['--es', 'probe', 'tls-head', '--es', 'tls-host', 'cloudflare-dns.com', '--es', 'tls-address', '1.1.1.1', '--ez', 'allow-public', 'true', '--ei', 'measured-runs', String(tlsSamples), '--ei', 'timeout-ms', '1500']]
] : [
    ['ipv6-http-tcp', ['--es', 'probe', 'http,tcp6', '--es', 'http-url', 'http://[fec0::2]:18080/bytes?size={size}', '--es', 'tcp-host', 'fec0::2', '--es', 'tcp-family', '6', '--ei', 'fixture-size', '1048576']],
    ['udp4-60000', ['--es', 'probe', 'udp4', '--es', 'udp-family', '4', '--ei', 'fixture-size', '60000']],
    ['udp6-1200', ['--es', 'probe', 'udp6', '--es', 'udp-host', 'fec0::2', '--es', 'udp-family', '6', '--ei', 'fixture-size', '1200']],
    ['https', ['--es', 'probe', 'http', '--es', 'http-url', 'https://example.com/', '--ez', 'allow-public', 'true']]
];
const apkPath = run('shell', 'pm', 'path', pkg).trim().split('\n')[0].replace(/^package:/, '');
const connectivity = run('shell', 'dumpsys', 'connectivity');
fs.writeFileSync(path.join(output, 'connectivity-before.txt'), connectivity);
const vpnOwnerVerified = connectivity.includes(`ni{VPN CONNECTED extra: VPN:${pkg}}`);
const provenance = { package: pkg, apkPath, sha256: run('shell', 'sha256sum', apkPath).trim().split(/\s+/)[0], vpnOwnerVerified, capturedAt: new Date().toISOString() };
if (!vpnOwnerVerified) throw new Error('Requested package does not own the connected VPN');
const results = [];
for (const [label, args] of cases) {
    const runId = `v4-${label}-${Date.now()}`;
    const launchArgs = ['shell', 'am', 'start', '-S', '-W', '-n', 'com.luna.deviceprobe/.DeviceProbeActivity', '--es', 'run-id', runId, '--ei', 'timeout-ms', '10000', ...args];
    const launch = run(...launchArgs);
    fs.writeFileSync(path.join(output, `${label}-launch.txt`), launch);
    let log = '';
    let complete = false;
    for (let attempt = 0; attempt < (kind === 'tls' ? 120 : 40); attempt++) {
        log = run('logcat', '-d', '-v', 'threadtime', '-s', 'LunaDeviceProbe:I', '*:S');
        if (log.includes(`BENCH_DONE runId=${runId} `)) { complete = true; break; }
        await delay(500);
    }
    const start = log.indexOf(`START runId=${runId} `);
    const end = log.indexOf(`BENCH_DONE runId=${runId} `);
    const selected = start >= 0 ? log.slice(log.lastIndexOf('\n', start) + 1, end < 0 ? undefined : log.indexOf('\n', end) < 0 ? undefined : log.indexOf('\n', end)) : log;
    fs.writeFileSync(path.join(output, `${label}.log`), selected + '\n');
    const samples = log.split('\n').filter(line => line.includes('BENCH_SAMPLE_JSON ')).map(line => JSON.parse(line.split('BENCH_SAMPLE_JSON ')[1])).filter(value => value.runId === runId);
    const network = selected.split('\n').filter(line => /ACTIVE_(NETWORK|CAPABILITIES|LINKS)/.test(line));
    const expectedSamples = kind === 'tls' ? tlsSamples : label === 'ipv6-http-tcp' ? 2 : 1;
    const passed = samples.filter(value => value.status === 'pass').length;
    const result = { label, runId, command: launchArgs, complete, expectedSamples, passed, failed: samples.filter(value => value.status !== 'pass').length, samples, network, pass: complete && samples.length === expectedSamples && passed === expectedSamples && network.some(line => line.includes('VPN')) };
    results.push(result);
    console.log(JSON.stringify({ label, passed, expectedSamples, pass: result.pass }));
}
const summary = { provenance, kind, results, passed: results.reduce((sum, value) => sum + value.passed, 0), expected: results.reduce((sum, value) => sum + value.expectedSamples, 0), pass: results.every(value => value.pass) };
fs.writeFileSync(path.join(output, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
if (!summary.pass) process.exitCode = 1;
