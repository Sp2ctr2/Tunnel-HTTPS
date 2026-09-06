#!/usr/bin/env python3
"""Import only public assets from a checksum-pinned CI artifact; never edit the app."""
from __future__ import annotations
import argparse
import hashlib
import html
import re
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit
from markdown_it import MarkdownIt

ROOT = Path(__file__).resolve().parents[2]
SITE = 'https://sp2ctr2.github.io/Tunnel-HTTPS/'
REPO = 'https://github.com/Sp2ctr2/Tunnel-HTTPS'
SEED_HASH = '376f86a2e90aab3ce386b88432f38025f0816adae367157c86c154c8f37caf17'
ASSETS = {'app-preview.webp', 'app-preview-ko.webp', 'hero-light.svg', 'hero-dark.svg', 'mark.svg', 'packet-path-light.svg', 'packet-path-dark.svg', 'site.css', 'site.js', 'social.png', 'release.json', 'signing-certificate.pem', 'test-summary.json'}
ALLOW = {'README.md', 'README.ko.md', 'docs/index.html', 'docs/404.html', 'docs/guide/index.html', 'docs/engineering/index.html', 'docs/privacy/index.html', 'docs/WEBSITE_SECURITY.md', 'docs/diagrams/packet-path.mmd', 'tools/site/browser_check.py', 'tools/site/validate.py', 'tools/site/requirements.txt', 'tools/site/social.html'}
ALLOW |= {'docs/assets/' + name for name in ASSETS}


def write(name: str, text: str) -> None:
    path = ROOT / name
    if path.is_symlink() or not path.resolve().is_relative_to(ROOT):
        raise ValueError('Unsafe publication path: ' + name)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + '\n', encoding='utf-8')


def import_seed(archive: Path) -> None:
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SEED_HASH:
        raise ValueError('Publication artifact checksum mismatch')
    with zipfile.ZipFile(archive) as z:
        if len(set(z.namelist())) != len(z.namelist()):
            raise ValueError('Duplicate archive entry')
        for name in sorted(ALLOW):
            entry = z.getinfo(name)
            if entry.file_size > 2_000_000 or ((entry.external_attr >> 16) & 0o170000) == 0o120000:
                raise ValueError('Unexpected asset type or size: ' + name)
            dest = ROOT / name
            if dest.is_symlink() or not dest.resolve().is_relative_to(ROOT):
                raise ValueError('Unsafe extraction target: ' + name)
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(z.read(entry))


def update_readmes() -> None:
    for name, label in [('README.md', 'Developer commands — expand to view'), ('README.ko.md', '개발자용 명령어 펼치기')]:
        text = (ROOT / name).read_text()
        text = text.replace('flowchart TB', 'flowchart LR')
        text = re.sub(r'^```(?:sh|bash)\n.*?^```[ \t]*$', lambda m: '<details>\n<summary>' + label + '</summary>\n\n' + m[0] + '\n\n</details>', text, flags=re.M | re.S)
        if name == 'README.md':
            text = text.replace('## The interface', '## New here? Start with the engineering story\n\n[**Below the connect button**](docs/launch/README.md) explains the implementation boundary, the local packet path, and the specific reviews that would help. [한국어 소개](docs/launch/README.ko.md) · [Report a reproducible issue](https://github.com/Sp2ctr2/Tunnel-HTTPS/issues).\n\n## The interface', 1)
        write(name, text)


def source_links(tokens: list, source: Path) -> None:
    for token in tokens:
        if token.type == 'link_open':
            href = token.attrGet('href') or ''
            u = urlsplit(href)
            if not u.scheme and u.path:
                candidate = (source.parent / u.path).resolve()
                if not candidate.is_relative_to(ROOT):
                    raise ValueError('Article link escapes checkout: ' + href)
                rel = candidate.relative_to(ROOT).as_posix()
                if rel == 'docs/launch/README.md':
                    target = SITE + 'story/'
                elif rel == 'docs/launch/README.ko.md':
                    target = SITE + 'story/ko/'
                else:
                    target = REPO + '/blob/main/' + rel
                token.attrSet('href', target + ('#' + u.fragment if u.fragment else ''))
        if token.children:
            source_links(token.children, source)


def article(locale: str) -> None:
    korean = locale == 'ko'
    source = ROOT / ('docs/launch/README.ko.md' if korean else 'docs/launch/README.md')
    title = '연결 버튼 아래에서' if korean else 'Below the connect button.'
    description = 'Android 로컬 네트워크 엔진의 구현 경계와 설계, 그리고 필요한 기술 리뷰.' if korean else 'Inside an Android network engine: packet handling, trust boundaries, and what to review.'
    path = 'story/ko/' if korean else 'story/'
    prefix = '../../' if korean else '../'
    head = (ROOT / 'docs/privacy/index.html').read_text().split('<body>', 1)[0]
    head = head.replace('lang="en"', 'lang="ko"' if korean else 'lang="en"')
    head = re.sub(r'<title>.*?</title>', '<title>' + title + ' · Tunnel HTTPS</title>', head)
    head = head.replace(SITE + 'privacy/', SITE + path)
    head = re.sub(r'(<meta (?:name="description"|property="og:description") content=")[^"]*', lambda m: m[1] + html.escape(description, quote=True), head)
    head = re.sub(r'(<meta property="og:title" content=")[^"]*', lambda m: m[1] + html.escape(title + ' · Tunnel HTTPS', quote=True), head)
    head = head.replace('property="og:type" content="website"', 'property="og:type" content="article"')
    head = head.replace('href="../assets/', 'href="' + prefix + 'assets/').replace('src="../assets/', 'src="' + prefix + 'assets/')
    head = head.replace('</head>', '<link rel="alternate" type="application/atom+xml" title="Tunnel HTTPS updates" href="' + prefix + 'feed.xml">\n</head>')
    markdown = MarkdownIt('commonmark', {'html': False}).enable('table')
    tokens = markdown.parse(source.read_text().split('\n', 1)[1].lstrip())
    source_links(tokens, source)
    body = markdown.renderer.render(tokens, markdown.options, {})
    nav = ('홈', '설치', 'English', '../') if korean else ('Home', 'Installation', '한국어', 'ko/')
    main = f'''<body><a class="skip" href="#main">{'본문 바로가기' if korean else 'Skip to content'}</a>
<header class="wrap nav"><a class="brand" href="{prefix}">tunnel / https</a><nav class="navlinks" aria-label="{'주 메뉴' if korean else 'Main navigation'}"><a href="{prefix}">{nav[0]}</a><a class="desktop" href="{prefix}guide/">{nav[1]}</a><a href="{nav[3]}" lang="{'en' if korean else 'ko'}">{nav[2]}</a><button class="theme" type="button" aria-label="Use dark theme">◐</button></nav></header>
<main id="main" tabindex="-1" class="wrap story"><header class="doc-head"><p class="eyebrow">ENGINEERING / SP2CTR2</p><h1>{title}</h1><p class="lede">{description}</p></header><article class="prose">{body}</article>
<section class="story-share" aria-label="{'프로젝트 공유' if korean else 'Share the project'}"><h2>{'프로젝트를 공유하세요.' if korean else 'Pass the code along.'}</h2><p>{'공식 링크만 공유합니다. 자동 게시나 추적은 하지 않습니다.' if korean else 'One canonical repository link. No automatic posting or tracking.'}</p><button class="button" type="button" data-share-copy>{'저장소 링크 복사' if korean else 'Copy repository link'}</button><p data-share-result role="status" aria-live="polite"></p><p><a href="{REPO}">{REPO}</a></p></section></main><footer class="wrap footer"><a href="{prefix}">Tunnel HTTPS</a><a href="{prefix}feed.xml">Atom feed</a><a href="{prefix}privacy/">Privacy</a></footer></body></html>'''
    write('docs/' + path + 'index.html', head + main)


def polish() -> None:
    css = (ROOT / 'docs/assets/site.css').read_text()
    css += '\n.story{max-width:960px}.story .doc-head{max-width:800px}.story .prose{max-width:74ch;margin:0 auto}.story .prose h2{font-size:clamp(1.6rem,4vw,2.35rem);line-height:1.15;margin-top:2.7rem}.story .prose p{line-height:1.8}.story .prose table{display:block;max-width:100%;overflow-x:auto;border-collapse:collapse;font-size:.9rem}.story th,.story td{padding:14px 16px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}.story-share{max-width:74ch;margin:56px auto;padding:28px 0;border-top:1px solid var(--line);overflow-wrap:anywhere}.story-share h2{font-size:1.7rem}html[lang=ko] .story{word-break:keep-all;overflow-wrap:anywhere}.prose a{text-underline-offset:3px}.prose a:focus-visible{outline:2px solid currentColor;outline-offset:4px}details>summary{cursor:pointer;line-height:1.55}details pre{margin-top:16px}\n'
    write('docs/assets/site.css', css)
    js = (ROOT / 'docs/assets/site.js').read_text()
    js += '''\n// Sharing requires a user gesture; no telemetry or network request is sent.
document.querySelectorAll('[data-share-copy]').forEach(button => button.addEventListener('click', async () => {
  const status = document.querySelector('[data-share-result]');
  const ko = document.documentElement.lang === 'ko';
  try { await navigator.clipboard.writeText('https://github.com/Sp2ctr2/Tunnel-HTTPS'); status.textContent = ko ? '링크를 복사했습니다.' : 'Repository link copied.'; }
  catch { status.textContent = ko ? '아래 저장소 링크를 직접 복사해 주세요.' : 'Copy the repository link shown below.'; }
}));\n'''
    write('docs/assets/site.js', js)
    for path in (ROOT / 'docs').rglob('*.html'):
        text = path.read_text()
        if path == ROOT / 'docs/index.html':
            text = text.replace('<div class="actions">', '<div class="actions"><a class="button" href="story/">The engineering story ↗</a>', 1)
        text = re.sub(r'<pre\b[^>]*>.*?</pre>', lambda m: '<details><summary>Developer commands — expand to view</summary>' + m[0] + '</details>', text, flags=re.S)
        if 'feed.xml' not in text:
            text = text.replace('</head>', '<link rel="alternate" type="application/atom+xml" title="Tunnel HTTPS updates" href="' + SITE + 'feed.xml"></head>')
        write(path.relative_to(ROOT).as_posix(), text)
    path = ROOT / 'tools/site/browser_check.py'
    text = path.read_text().replace("'', 'guide/', 'engineering/', 'privacy/', '404.html'", "'', 'guide/', 'engineering/', 'privacy/', '404.html', 'story/', 'story/ko/'")
    write('tools/site/browser_check.py', text)
    write('docs/SITE.md', '# Public website maintenance\n\nThe website is static HTML, CSS and JavaScript in docs/. Edit those public files directly. The source-backed introduction is in launch/README.md and launch/README.ko.md. Review both languages together. The import script is a one-time recovery path, not the normal site build.\n\nBefore publication, run the documentation renderer and browser/static checks in tools/docs/ and tools/site/. Do not add remote tracking, inline scripts, unverified benchmarks, or render URL input as HTML. Interface images are explicitly labelled Chromium previews, not Android device evidence. Release metadata must describe an actually published artifact.\n')
    write('docs/robots.txt', 'User-agent: *\nAllow: /\nSitemap: ' + SITE + 'sitemap.xml\n')
    ns = 'http://www.sitemaps.org/schemas/sitemap/0.9'
    ET.register_namespace('', ns)
    tree = ET.Element('{' + ns + '}urlset')
    for u in ['', 'engineering/', 'guide/', 'privacy/', 'story/', 'story/ko/']:
        ET.SubElement(ET.SubElement(tree, '{' + ns + '}url'), '{' + ns + '}loc').text = SITE + u
    write('docs/sitemap.xml', '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(tree, encoding='unicode'))
    ns = 'http://www.w3.org/2005/Atom'
    ET.register_namespace('', ns)
    feed = ET.Element('{' + ns + '}feed')
    def elt(parent, name, text):
        ET.SubElement(parent, '{' + ns + '}' + name).text = text
    stamp = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')
    elt(feed, 'title', 'Tunnel HTTPS — engineering updates')
    elt(feed, 'id', SITE + 'feed.xml')
    elt(feed, 'updated', stamp)
    ET.SubElement(feed, '{' + ns + '}link', {'href': SITE + 'feed.xml', 'rel': 'self'})
    author = ET.SubElement(feed, '{' + ns + '}author')
    elt(author, 'name', 'Sp2ctr2')
    entry = ET.SubElement(feed, '{' + ns + '}entry')
    elt(entry, 'title', 'Below the connect button: building an Android network engine')
    elt(entry, 'id', SITE + 'story/')
    elt(entry, 'updated', stamp)
    ET.SubElement(entry, '{' + ns + '}link', {'href': SITE + 'story/'})
    elt(entry, 'summary', 'Explore the local packet path, the platform boundary, and the reviews that would help Tunnel HTTPS.')
    write('docs/feed.xml', '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(feed, encoding='unicode'))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact', type=Path)
    args = parser.parse_args()
    import_seed(args.artifact)
    update_readmes()
    article('en')
    article('ko')
    polish()
    print('Prepared public assets and bilingual engineering story; application source was not changed.')


if __name__ == '__main__':
    main()
