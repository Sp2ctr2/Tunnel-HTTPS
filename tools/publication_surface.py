#!/usr/bin/env python3
"""Finish public presentation without altering the APK or any Android source."""
from pathlib import Path
import hashlib
import html
import re
import ssl

ROOT = Path(__file__).resolve().parents[1]
certificate = ROOT / 'docs/assets/signing-certificate.pem'
original = certificate.read_text()
normalized = '\n'.join(line.rstrip() for line in original.splitlines()) + '\n'
expected = 'fbd8c2508d81843ff6f7cdaaba62e85c935fd8a0e1e8aac465a29b7b12a16e46'
for value in (original, normalized):
    if hashlib.sha256(ssl.PEM_cert_to_DER_cert(value)).hexdigest() != expected:
        raise ValueError('Public certificate identity changed')
certificate.write_text(normalized)

count = 0
for path in (ROOT / 'docs').rglob('*.html'):
    source = path.read_text()
    def collapse(match):
        global count
        count += 1
        commands = re.sub(r'<br\s*/?>', '\n', match.group(1))
        commands = html.escape(html.unescape(commands), quote=False)
        return '<details><summary>Build from source — commands</summary><pre><code>' + commands + '</code></pre></details>'
    changed = re.sub(r'<code>(git clone .*?)</code>', collapse, source, flags=re.S)
    path.write_text(changed.rstrip() + '\n')

path = ROOT / 'tools/site/browser_check.py'
source = path.read_text()
source = source.replace("path in ('','guide/','engineering/')", "path in ('','guide/','engineering/','story/','story/ko/')")
source = source.replace('path.strip("/") or "home"', 'path.strip("/").replace("/", "-") or "home"')
marker = "                    results.append({'page':path or '/','theme':mode,'width':width,'status':'PASS'})"
check = '''                    for code in page.locator('code').all():
                        text = code.inner_text()
                        if any(text.lstrip().startswith(prefix) for prefix in ('git clone ', './gradlew ', 'adb install ', 'cd Tunnel-HTTPS')):
                            assert code.evaluate('(element) => Boolean(element.closest("details"))'), ('command outside disclosure', path)
                            assert not code.is_visible(), ('commands visible before expansion', path, width)
'''
if source.count(marker) != 1:
    raise ValueError('Browser test insertion point changed')
source = source.replace(marker, check + marker)
source = source.replace("'no-JS install instructions']", "'no-JS install instructions','developer commands initially collapsed']")
path.write_text(source.rstrip() + '\n')
print(f'Collapsed {count} source command panel(s); certificate DER identity preserved; visual tests strengthened.')
