"""Capture the repository's actual app UI. No native bridge or traffic is simulated."""
from pathlib import Path
import argparse, hashlib, json, os
from playwright.sync_api import sync_playwright
ROOT=Path(__file__).resolve().parents[2]

def capture(output: Path):
    output.mkdir(parents=True,exist_ok=True)
    source=ROOT/'app/src/main/assets/index.html'
    records=[]
    with sync_playwright() as p:
        executable=os.environ.get('CHROMIUM_PATH')
        browser=p.chromium.launch(**({'executable_path':executable} if executable else {}))
        for locale in ('en','ko'):
            context=browser.new_context(viewport={'width':390,'height':780},device_scale_factor=2,locale=locale+'-'+('US' if locale=='en' else 'KR'),reduced_motion='reduce')
            context.route('https://**',lambda route:route.abort())
            page=context.new_page()
            page.set_content(source.read_text(encoding="utf-8-sig"))
            page.locator('.boot').wait_for(state='hidden')
            for name,tab in (('connection','secure'),('activity','insights'),('settings','settings')):
                page.locator('#nav-'+tab).click()
                page.locator('#'+tab+'.active').wait_for()
                page.screenshot(path=str(output/f'{locale}-{name}.png'))
                records.append({'image':f'{locale}-{name}.png','locale':locale,'source':'app/src/main/assets/index.html','state':'Disconnected; no native Android bridge, traffic or synthetic metrics','source_sha256':hashlib.sha256(source.read_bytes()).hexdigest()})
            context.close()
        browser.close()
    (output/'provenance.json').write_text(json.dumps(records,indent=2)+'\n')
if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,required=True)
    capture(ap.parse_args().output)
