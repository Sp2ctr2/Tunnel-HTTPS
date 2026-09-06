#!/usr/bin/env python3
"""Render actual Mermaid SVGs and exercise the native GitHub README HTML."""
import argparse
import json
from pathlib import Path

from playwright.sync_api import sync_playwright


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('documentation-render-results'))
    parser.add_argument('--mermaid-js', type=Path, required=True)
    args = parser.parse_args()
    output = args.output
    report = json.loads((output / 'report.json').read_text())
    native = output / 'github-readme.html'
    source = native if native.exists() else output / 'previews/README.md.html'
    css = 'body{font:16px/1.6 system-ui,sans-serif;color:#1f2328;margin:0;padding:24px}main{max-width:960px;margin:auto;min-width:0}pre{padding:16px;background:#f6f8fa;overflow:auto;border-radius:6px}code{font-family:monospace}table{display:block;max-width:100%;overflow:auto;border-collapse:collapse}td,th{border:1px solid #d0d7de;padding:6px 12px}details{margin:16px 0;border:1px solid #d0d7de;padding:12px;border-radius:6px}summary{cursor:pointer;font-weight:600}img,svg{max-width:100%;height:auto}a{color:#0969da}p,li,td{overflow-wrap:anywhere}'
    results = []
    with sync_playwright() as p:
        browser = p.chromium.launch()
        context = browser.new_context(viewport={'width': 1100, 'height': 900})
        # Documentation must not contact third-party resources during tests.
        context.route('**/*', lambda route: route.abort())
        page = context.new_page()
        page.set_content('<!doctype html><html><head><meta charset="utf-8"><style>' + css + '</style></head><body><main>' + source.read_text() + '</main></body></html>')
        page.add_script_tag(path=str(args.mermaid_js.resolve()))
        page.evaluate("mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'neutral',flowchart:{htmlLabels:false}})")
        for index, entry in enumerate(report['diagrams']):
            text = (output / entry['file']).read_text()
            svg = page.evaluate("async ([id,text]) => {await mermaid.parse(text); return (await mermaid.render(id,text)).svg;}", [f'checked-diagram-{index}', text])
            assert '<svg' in svg and '</svg>' in svg
            (output / f'diagram-{index + 1}.svg').write_text(svg)
            if entry['source'] == 'README.md':
                replaced = page.evaluate("([text,svg]) => {const pre=[...document.querySelectorAll('main pre')].find(p=>p.textContent.trim()===text.trim());if(!pre)return false;const doc=new DOMParser().parseFromString(svg,'image/svg+xml');pre.replaceWith(document.importNode(doc.documentElement,true));return true;}", [text, svg])
                assert replaced, 'Native README diagram block was not found'
            results.append({'source': entry['source'], 'status': 'PASS'})
        assert page.locator('details').count() >= 2
        commands = ('adb install -r', './gradlew :app:assembleDebug')
        for text in commands:
            block = page.locator('pre').filter(has_text=text)
            assert block.count() == 1 and not block.is_visible(), 'Command should be collapsed: ' + text
            details = block.locator('xpath=ancestor::details[1]')
            details.locator('summary').click()
            assert block.is_visible(), 'Command should be readable after expansion'
            details.locator('summary').click()
        for width in (320, 768, 1440):
            page.set_viewport_size({'width': width, 'height': 900})
            assert not page.evaluate('document.documentElement.scrollWidth > innerWidth + 1'), f'Preview overflow at {width}'
        page.set_viewport_size({'width': 1100, 'height': 900})
        page.screenshot(path=str(output / 'readme-rendered.png'), full_page=True)
        browser.close()
    result = {'status': 'PASS', 'diagrams': results, 'command_sections': 2, 'preview_widths': [320, 768, 1440], 'native_github_html': native.exists(), 'scope': 'Mermaid 11.12.0 SVG rendering and README HTML interactions in Chromium; preview CSS is not the GitHub website UI.'}
    (output / 'browser-report.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
