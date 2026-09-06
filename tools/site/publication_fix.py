#!/usr/bin/env python3
"""Apply reviewed publication fixes to the verified generator, not to generated output."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
path = ROOT / 'tools/site/enhance.py'
source = path.read_text()
marker = "EXTRA_CSS = '''"
assert source.count(marker) == 1
css = '''
/* Grid children must be allowed to shrink below their intrinsic content size. */
.hero > *, .split > *, .learning > *, .download > *, .interface > *, .build-evidence > * { min-width: 0; }
.preview-strip { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); justify-items: center; gap: 24px; }
.interface .preview-strip img { min-width: 0; width: 100%; max-width: 250px; height: auto; }
@media (max-width: 420px) {
  .preview-strip { grid-template-columns: minmax(0, 1fr); }
  .interface .preview-strip img { max-width: 245px; }
  .hero h1, h2, h3 { overflow-wrap: anywhere; }
}
'''
# Append within the existing stylesheet literal so it overrides earlier layout rules.
end = source.index("\n'''", source.index(marker) + len(marker))
source = source[:end] + css + source[end:]
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
path.write_text(source)
print('Publication layout fixes applied; all original browser/security assertions remain enforced.')
