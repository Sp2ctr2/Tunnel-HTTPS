#!/usr/bin/env python3
"""One-time, owner-authorized release signing. Never writes private material to Git.

Only the encrypted recovery envelope is checkpointed to a separate branch.
The corresponding RSA private recovery key is held outside GitHub by the owner.
A previously checkpointed identity is never silently replaced.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = os.environ['GITHUB_REPOSITORY']
AAD = b'TunnelHTTPS/signing/v1'
VERSION = '0.9.0-beta.1'
TAG = 'v' + VERSION
RECOVERY_BRANCH = 'signing-recovery'
ROOT = Path.cwd()
DIST = ROOT / 'dist'
PRIVATE = Path(os.environ['RUNNER_TEMP']) / 'tunnel-signing'

def run(*args, **kw):
    return subprocess.run(args, check=True, text=True, **kw)

def api(endpoint, payload=None):
    cmd = ['gh', 'api', f'repos/{REPO}/{endpoint}']
    if payload is not None:
        cmd += ['--method', 'POST', '--input', '-']
    p = subprocess.run(cmd, input=json.dumps(payload) if payload is not None else None,
                       text=True, capture_output=True)
    if p.returncode:
        raise RuntimeError(f'GitHub operation failed: {endpoint}: {p.stderr[:300]}')
    return json.loads(p.stdout)

def exists(endpoint):
    p = subprocess.run(['gh', 'api', f'repos/{REPO}/{endpoint}'], capture_output=True, text=True)
    if p.returncode == 0:
        return True
    if '404' in p.stderr:
        return False
    raise RuntimeError(f'Could not safely establish absence: {endpoint}: {p.stderr[:300]}')

def prepare():
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    if REPO != 'Sp2ctr2/Tunnel-HTTPS' or os.environ.get('GITHUB_REF_NAME') != 'oss-flagship-polish':
        raise RuntimeError('One-time bootstrap is restricted to the authorized repository and branch')
    if exists(f'releases/tags/{TAG}'):
        raise RuntimeError('This release already exists. Preserve its signing identity; do not bootstrap again.')
    recovery_path = f'{TAG}.encrypted.json'
    if exists(f'contents/{recovery_path}?ref={RECOVERY_BRANCH}'):
        raise RuntimeError('Signing identity already checkpointed. Recover it; never generate a replacement.')
    PRIVATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    DIST.mkdir(exist_ok=True)
    password = secrets.token_urlsafe(48)
    print('::add-mask::' + password, flush=True)
    keystore = PRIVATE / 'tunnel-https-release.p12'
    env = os.environ.copy()
    env['KEY_PASSWORD'] = password
    run('keytool', '-genkeypair', '-noprompt', '-storetype', 'PKCS12', '-keystore', str(keystore),
        '-alias', 'tunnel-https', '-keyalg', 'RSA', '-keysize', '4096', '-sigalg', 'SHA256withRSA',
        '-validity', '10000', '-dname', 'CN=Tunnel HTTPS, O=Sp2ctr2',
        '-storepass:env', 'KEY_PASSWORD', '-keypass:env', 'KEY_PASSWORD', env=env)
    keystore.chmod(0o600)
    run('keytool', '-exportcert', '-rfc', '-keystore', str(keystore), '-alias', 'tunnel-https',
        '-storepass:env', 'KEY_PASSWORD', '-file', str(DIST / 'signing-certificate.pem'), env=env)
    payload = json.dumps({'schema': 1, 'repository': REPO, 'alias': 'tunnel-https',
        'store_password': password, 'key_password': password, 'store_type': 'PKCS12',
        'keystore_base64': base64.b64encode(keystore.read_bytes()).decode()}).encode()
    public = serialization.load_pem_public_key((ROOT / 'tools/release/delivery-public.pem').read_bytes())
    key, nonce = AESGCM.generate_key(bit_length=256), secrets.token_bytes(12)
    encrypted = AESGCM(key).encrypt(nonce, payload, AAD)
    wrapped = public.encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),
                                             algorithm=hashes.SHA256(), label=None))
    b64 = lambda x: base64.b64encode(x).decode()
    envelope = json.dumps({'schema': 1, 'algorithm': 'RSA-OAEP-SHA256+AES-256-GCM',
        'aad': AAD.decode(), 'encrypted_key': b64(wrapped), 'nonce': b64(nonce),
        'ciphertext': b64(encrypted)}, indent=2) + '\n'
    sha = run('git', 'rev-parse', 'HEAD', capture_output=True).stdout.strip()
    if not exists(f'git/ref/heads/{RECOVERY_BRANCH}'):
        api('git/refs', {'ref': f'refs/heads/{RECOVERY_BRANCH}', 'sha': sha})
    # Contents creation uses PUT, and fails rather than overwriting an existing identity.
    recovery_payload = {'message': 'security: checkpoint owner-encrypted signing recovery',
        'branch': RECOVERY_BRANCH, 'content': b64(envelope.encode())}
    p = subprocess.run(['gh', 'api', f'repos/{REPO}/contents/{recovery_path}', '--method', 'PUT', '--input', '-'],
        input=json.dumps(recovery_payload), capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError('Encrypted recovery checkpoint failed; refusing to sign any release')
    variables = {'TUNNELHTTPS_STORE_FILE': str(keystore), 'TUNNELHTTPS_STORE_PASSWORD': password,
        'TUNNELHTTPS_KEY_ALIAS': 'tunnel-https', 'TUNNELHTTPS_KEY_PASSWORD': password}
    with open(os.environ['GITHUB_ENV'], 'a') as f:
        for name, value in variables.items():
            f.write(f'{name}={value}\n')
    print('Signing identity created; encrypted recovery checkpoint verified. No private key was published.')

def package():
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes
    apk = ROOT / 'app/build/outputs/apk/release/app-release.apk'
    if not apk.is_file():
        raise RuntimeError('Expected signed release APK missing')
    target = DIST / f'TunnelHTTPS-{VERSION}.apk'
    shutil.copy2(apk, target)
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '')))
    apksigner = sdk / 'build-tools/36.0.0/apksigner'
    signature = run(str(apksigner), 'verify', '--verbose', '--print-certs', str(target), capture_output=True).stdout
    cert = x509.load_pem_x509_certificate((DIST / 'signing-certificate.pem').read_bytes())
    fingerprint = cert.fingerprint(hashes.SHA256()).hex()
    if fingerprint not in signature.lower():
        raise RuntimeError('APK certificate does not match the preserved signing identity')
    (DIST / 'signature-verification.txt').write_text(signature)
    tests = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0, 'suites': 0}
    for path in (ROOT / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        tests['suites'] += 1
        for field in ('tests', 'failures', 'errors', 'skipped'):
            tests[field] += int(suite.get(field, '0'))
    if not tests['tests'] or tests['failures'] or tests['errors']:
        raise RuntimeError('A complete passing test run is required')
    (DIST / 'test-summary.json').write_text(json.dumps(tests, indent=2) + '\n')
    sha = run('git', 'rev-parse', 'HEAD', capture_output=True).stdout.strip()
    metadata = {'schema': 1, 'version': VERSION, 'version_code': 11,
        'application_id': 'com.tunnelvpn.app', 'min_sdk': 24, 'target_sdk': 36,
        'source_commit': sha, 'build_run': f'https://github.com/{REPO}/actions/runs/{os.environ["GITHUB_RUN_ID"]}',
        'apk': target.name, 'apk_sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
        'certificate_sha256': fingerprint, 'tests': tests,
        'validation_scope': 'GitHub-hosted JVM tests, Android lint, minified signed release build; not physical-device certification'}
    (DIST / 'release.json').write_text(json.dumps(metadata, indent=2) + '\n')
    checksum_files = sorted(p for p in DIST.iterdir() if p.is_file() and p.name != 'SHA256SUMS')
    (DIST / 'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in checksum_files))
    notes = f'''## Tunnel HTTPS {VERSION}

First signed public beta of the on-device Android network engine. **Android 7.0+ (API 24)**. Korean and English interface resources. No developer-operated remote VPN gateway.

### Inside
IPv4/IPv6 normalization and bounded fragment handling; local TCP/UDP relays; validated DoH, DNS64/NAT64; adaptive TLS ClientHello fragmentation; QUIC-aware fallback; local Turbo learner; Aegis neural policy in shadow mode.

### Verification
- {tests['tests']} JVM tests across {tests['suites']} suites; {tests['failures']} failures, {tests['errors']} errors, {tests['skipped']} skipped.
- Debug and release lint; minified, non-debuggable release build.
- APK signature verified with Android apksigner. Exact provenance is in `release.json`.
- Physical-device/carrier, long-run and real-network compatibility are **not certified by this build**. This is a beta, not a guarantee of connectivity or anonymity.

### Install
Download `{target.name}` below. Allow installation for the downloading app only when Android asks. Grant VPN consent inside Tunnel HTTPS. A different test signing key for the same package may require uninstalling the old build first; that removes its local settings. Future official versions must use this release signing identity.

### Verify
```sh
sha256sum -c SHA256SUMS
apksigner verify --print-certs {target.name}
```
APK SHA-256: `{metadata['apk_sha256']}`

Signing certificate SHA-256: `{fingerprint}`

Source: `{sha}`. [Build evidence]({metadata['build_run']}).

### Boundaries
This app does not change your public IP/country, add a remote encrypted VPN gateway, decrypt HTTPS, or promise universal bypass. DNS providers and destination services still receive the requests sent to them. Aegis is observation-only. See the repository's support and privacy documentation.
'''
    notes_path = PRIVATE / 'release-notes.md'
    notes_path.write_text(notes)
    run('gh', 'release', 'create', TAG, '--repo', REPO, '--target', sha,
        '--title', f'Tunnel HTTPS {VERSION}', '--draft', '--prerelease',
        '--notes-file', str(notes_path), *[str(p) for p in sorted(DIST.iterdir()) if p.is_file()])
    print(json.dumps(metadata, indent=2))

if __name__ == '__main__':
    {'prepare': prepare, 'package': package}[sys.argv[1]]()
