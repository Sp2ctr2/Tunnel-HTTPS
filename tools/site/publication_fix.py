#!/usr/bin/env python3
"""Apply reviewed publication fixes to the verified generator, not to generated output."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
path = ROOT / 'tools/site/enhance.py'
source = path.read_text()
marker = "EXTRA_CSS = '''"
assert source.count(marker) == 1
css = '''
/* Bound intrinsic sizes without hiding content or shrinking readable type. */
.hero > *, .split > *, .learning > *, .download > *, .interface > *, .build-evidence > *, .doc-layout > * { min-width: 0; }
.prose section, .prose .table-wrap { min-width: 0; max-width: 100%; }
.prose .table-wrap { overflow-x: auto; overscroll-behavior-x: contain; }
.preview-strip { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); justify-items: center; gap: 24px; }
.interface .preview-strip img { min-width: 0; width: 100%; max-width: 250px; height: auto; }
@media (max-width: 1100px) {
  .doc-layout { grid-template-columns: minmax(0, 1fr); gap: 28px; }
  .toc { position: static; display: flex; flex-wrap: wrap; gap: 8px 20px; border-bottom: 1px solid var(--line); padding-bottom: 18px; }
  .toc a { padding: 8px 0; }
}
@media (max-width: 420px) {
  .nav { flex-wrap: wrap; row-gap: 12px; padding-block: 18px; }
  .navlinks { width: 100%; min-width: 0; justify-content: space-between; gap: 12px; }
  .navlinks a, .navlinks a:first-child { display: inline-flex; align-items: center; min-height: 44px; }
  .preview-strip { grid-template-columns: minmax(0, 1fr); }
  .interface .preview-strip img { max-width: 245px; }
  .hero h1, h2, h3 { overflow-wrap: anywhere; }
}
'''
end = source.index("\n'''", source.index(marker) + len(marker))
source = source[:end] + css + source[end:]
assert source.count("put('docs/assets/site.css',css)") == 1
source = source.replace("put('docs/assets/site.css',css)", "put('docs/assets/site.css', css.rstrip() + chr(10))")
compile(source, str(path), 'exec')
path.write_text(source)
path = ROOT / 'tools/site/browser_check.py'
source = path.read_text()
old = "                    assert not page.evaluate('document.documentElement.scrollWidth > innerWidth + 1'),('horizontal page overflow',path,mode,width)"
assert source.count(old) == 1
new = '''                    if page.evaluate('document.documentElement.scrollWidth > innerWidth + 1'):
                        details = page.evaluate("""() => ({viewport:innerWidth, scroll:document.documentElement.scrollWidth, elements:[...document.querySelectorAll('body *')].map(e=>({tag:e.tagName, class:String(e.className), left:e.getBoundingClientRect().left, right:e.getBoundingClientRect().right, width:e.getBoundingClientRect().width, text:e.textContent.slice(0,80)})).filter(e=>e.right>innerWidth+1 || e.left < -1).slice(0,80)})""")
                        print('VIEWPORT_FAILURE', path, mode, width, json.dumps(details), flush=True)
                        page.screenshot(path=str(REPORT/f'overflow-{path.strip("/") or "home"}-{mode}-{width}.png'),full_page=True)
                        raise AssertionError(('horizontal page overflow', path, mode, width))'''
source = source.replace(old, new)
assert source.count('page.wait_for_function(') == 4
source = source.replace('page.wait_for_function(', 'wait_condition(page, ')
assert source.count('import argparse\n') == 1
source = source.replace('import argparse\n', 'import argparse\nimport time\n')
helper = '''def wait_condition(page, expression: str, timeout: int = 10000):
    """Poll a fixed test expression through automation; leave page CSP enabled."""
    deadline = time.monotonic() + timeout / 1000
    while time.monotonic() < deadline:
        if page.evaluate(expression):
            return
        page.wait_for_timeout(100)
    raise AssertionError(('Browser condition timed out', expression))

'''
assert source.count('def run():') == 1
source = source.replace('def run():', helper + 'def run():')
compile(source, str(path), 'exec')
path.write_text(source)
print('Publication layout and CSP-aware test fixes applied; all application security assertions remain enforced.')
