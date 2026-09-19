#!/usr/bin/env python3
"""Build a network-free code landscape from a Git snapshot or local directory.
Only reads code; never imports or executes it. Python 3.10+, standard library only.
"""
from __future__ import annotations
import argparse, hashlib, json, re, subprocess
from pathlib import Path

EXTENSIONS = {'.kt':'Kotlin','.kts':'Kotlin','.java':'Java','.py':'Python','.js':'JavaScript','.mjs':'JavaScript','.ts':'TypeScript','.tsx':'TypeScript','.jsx':'JavaScript','.html':'HTML','.htm':'HTML','.css':'CSS','.scss':'CSS','.md':'Markdown','.xml':'XML','.svg':'XML','.json':'JSON','.yml':'YAML','.yaml':'YAML','.sh':'Shell','.bat':'Shell','.rs':'Rust','.go':'Go','.c':'C','.h':'C','.cpp':'C++','.hpp':'C++','.toml':'TOML'}
SKIP = {'.git','node_modules','.gradle','build','dist','.idea','.venv','venv','__pycache__','target'}

def language(path: str) -> str:
    return EXTENSIONS.get(Path(path).suffix.lower(), 'Shell' if Path(path).name == 'gradlew' else 'Text')

def mask_comments(text: str, lang: str) -> str:
    # Preserve newlines and character positions. This is intentionally not a compiler AST.
    if lang in {'Kotlin','Java','JavaScript','TypeScript','C','C++','Rust','Go','CSS'}:
        pattern = r'"""[\s\S]*?"""|/\*[\s\S]*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27|`(?:\\.|[^`\\])*`'
    elif lang == 'Python':
        pattern = r'"""[\s\S]*?"""|\x27\x27\x27[\s\S]*?\x27\x27\x27|#[^\n]*|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27'
    else:
        return text
    return re.sub(pattern, lambda m: re.sub(r'[^\n\r]', ' ', m.group()), text)

def symbols(text: str, lang: str) -> list[dict]:
    masked = mask_comments(text, lang)
    found = []
    for i, line in enumerate(masked.splitlines(), 1):
        m = None
        if lang == 'Kotlin':
            m = re.search(r'\b(class|interface|object|fun)\s+(?:<[^>]*>\s*)?(?:[\w<>?]+\.)?([A-Za-z_]\w*)', line)
            if m and m.group(1) == 'object' and m.group(2) in {'val','var'}: m = None
        elif lang == 'Python':
            m = re.search(r'^\s*(?:async\s+)?(def|class)\s+([A-Za-z_]\w*)', line)
        elif lang in {'JavaScript','TypeScript'}:
            m = re.search(r'\b(function|class|interface)\s+([A-Za-z_$][\w$]*)', line)
        elif lang in {'Java','C','C++','Go','Rust'}:
            m = re.search(r'\b(class|interface|enum|struct|fn|func)\s+([A-Za-z_]\w*)', line)
            if not m and lang == 'Java':
                j = re.search(r'^\s*(?:(?:public|private|protected|static|final|synchronized|abstract|native)\s+)+(?:[\w<>\[\],?]+\s+)+([A-Za-z_]\w*)\s*\(', line)
                if j: found.append({'name':j.group(1),'kind':'method','line':i})
        if m: found.append({'name':m.group(2),'kind':m.group(1),'line':i})
    return found

def enrich(data: dict) -> dict:
    seen = set()
    for f in data['files']:
        if f['path'] in seen: raise ValueError('Duplicate path: '+f['path'])
        seen.add(f['path'])
        f['language'] = 'Binary' if f.get('source') is None else language(f['path'])
        f['binary'] = f.get('source') is None
        if f['binary']:
            f.update(lines=0,nonblank=0,symbols=[],sections=[])
            continue
        raw = f['source'].encode('utf-8')
        blob_hash = hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if f.get('sha') and f['sha'] != blob_hash:
            raise ValueError('Git blob integrity mismatch: '+f['path'])
        f['sha'] = blob_hash
        f['bytes'] = len(raw)
        lines = f['source'].splitlines()
        f['lines'] = len(lines)
        f['nonblank'] = sum(bool(x.strip()) for x in lines)
        f['symbols'] = symbols(f['source'], f['language'])
        f['sections'] = []
        # Exact contiguous non-overlapping 32-line pages, never synthetic code.
        for start in range(0, len(lines), 32):
            end = min(start + 32, len(lines))
            near = [s for s in f['symbols'] if start < s['line'] <= end]
            prior = [s for s in f['symbols'] if s['line'] <= start]
            name = near[0]['name'] if near else (prior[-1]['name'] if prior else '')
            f['sections'].append({'start':start+1,'end':end,'nonblank':sum(bool(x.strip()) for x in lines[start:end]),'name':name})
    data['metrics'] = {'files':len(data['files']),'textFiles':sum(not f['binary'] for f in data['files']),'bytes':sum(f['bytes'] for f in data['files']),'lines':sum(f['lines'] for f in data['files']),'nonblank':sum(f['nonblank'] for f in data['files']),'symbols':sum(len(f['symbols']) for f in data['files']),'pages':sum(len(f['sections']) for f in data['files'])}
    data['schema'] = 1
    data['symbolMethod'] = 'Comment/string-masked declaration patterns; not a compiler AST.'
    return data

def scan(root: Path) -> dict:
    root = root.resolve()
    if not root.is_dir(): raise ValueError('Source directory does not exist')
    files = []
    try:
        tracked = subprocess.check_output(['git','-C',str(root),'ls-files','-z'],stderr=subprocess.DEVNULL).decode().split('\0')
        paths = [root/p for p in tracked if p]
        commit = subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],stderr=subprocess.DEVNULL).decode().strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        paths = [p for p in root.rglob('*') if p.is_file() and not any(x in SKIP for x in p.relative_to(root).parts)]
        commit = 'local-worktree'
    for p in sorted(paths):
        if p.is_symlink() or not p.is_file(): continue
        raw = p.read_bytes()
        if len(raw) > 32*1024*1024: text = None
        else:
            try: text = raw.decode('utf-8') if b'\0' not in raw else None
            except UnicodeDecodeError: text = None
        files.append({'path':p.relative_to(root).as_posix(),'bytes':len(raw),'source':text,'sha':hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()})
    # HEAD identifies the base, not necessarily the uncommitted worktree contents.
    return {'repo':root.name,'commit':commit,'commitDate':'','worktree':True,'files':files}

def build(data: dict, vendor: Path, out: Path) -> None:
    base = Path(__file__).resolve().parent
    data = enrich(data)
    template = (base/'template.html').read_text(encoding='utf-8')
    replacements = {
        '@@CSS@@':(base/'style.css').read_text(encoding='utf-8'),
        '@@VENDOR@@':vendor.read_text(encoding='utf-8'),
        '@@DATA@@':json.dumps(data, ensure_ascii=False,separators=(',',':')).replace('<','\\u003c').replace('\u2028','\\u2028').replace('\u2029','\\u2029'),
        '@@CORE@@':(base/'core.js').read_text(encoding='utf-8'),
        '@@APP@@':(base/'app.js').read_text(encoding='utf-8')
    }
    for token, text in replacements.items():
        if token in {'@@VENDOR@@','@@CORE@@','@@APP@@'}:
            text = re.sub(r'</script', r'<\\/script', text, flags=re.IGNORECASE)
        template = template.replace(token,text)
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(template,encoding='utf-8')
    print(json.dumps(data['metrics'],indent=2))
    print(f'Built {out} ({out.stat().st_size:,} bytes); all text sources embedded, zero network dependencies.')

if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__)
    group = ap.add_mutually_exclusive_group(required=True)
    group.add_argument('--snapshot',type=Path)
    group.add_argument('--repo',type=Path)
    ap.add_argument('--vendor',type=Path,default=Path(__file__).parent/'vendor.js')
    ap.add_argument('--out',type=Path,default=Path('Tunnel_Scope.html'))
    args = ap.parse_args()
    try:
        data = json.loads(args.snapshot.read_text(encoding='utf-8')) if args.snapshot else scan(args.repo)
        build(data,args.vendor,args.out)
    except (OSError, ValueError, KeyError) as e:
        ap.exit(1,f'Build failed: {e}\n')
