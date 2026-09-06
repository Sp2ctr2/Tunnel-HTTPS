#!/usr/bin/env python3
"""Reject Markdown syntax/commands leaking into prose; never execute documentation."""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import subprocess
from html.parser import HTMLParser
from pathlib import Path

from markdown_it import MarkdownIt

MARKDOWN = MarkdownIt("commonmark", {"html": True}).enable(["table", "strikethrough"])
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}
COMMAND = re.compile(
    r"(?:\./gradlew|gradlew\.bat)\s+[:\w-]|"
    r"\badb\s+(?:install|shell|devices|pull|push|reverse|forward)\b|"
    r"(?m:^\s*(?:\$\s+)?(?:git\s+(?:clone|checkout|switch|commit|push|pull)|"
    r"(?:python3?|node|bash|sh)\s+(?:\./|tools/)|"
    r"(?:npm|pnpm|yarn)\s+(?:install|run|test|build)|export\s+\w+=|chmod\s+\+x\b|curl\s+-))"
)
DIAGRAM = re.compile(r"(?m)^\s*(?:flowchart\s+(?:LR|RL|TD|TB|BT)\b|graph\s+(?:LR|RL|TD|TB|BT)\b|sequenceDiagram\b)")


class Surface(HTMLParser):
    """Inspect text nodes, not HTML source; code/script/style are not prose."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.stack: list[str] = []
        self.prose: list[str] = []
        self.blocks: list[dict] = []
        self.active: list[dict] = []
        self.details = 0

    def handle_starttag(self, tag: str, attrs: list) -> None:
        if tag == "details":
            self.details += 1
        if tag == "pre":
            block = {"text": "", "collapsed": "details" in self.stack}
            self.blocks.append(block)
            self.active.append(block)
        if tag not in VOID:
            self.stack.append(tag)

    def handle_startendtag(self, tag: str, attrs: list) -> None:
        pass

    def handle_endtag(self, tag: str) -> None:
        if tag == "pre" and self.active:
            self.active.pop()
        if tag in self.stack:
            del self.stack[len(self.stack) - 1 - self.stack[::-1].index(tag):]

    def handle_data(self, data: str) -> None:
        if self.active:
            self.active[-1]["text"] += data
        if not any(tag in self.stack for tag in ("pre", "code", "script", "style", "template")):
            self.prose.append(data)

    def problems(self) -> list[str]:
        problems = []
        for text in self.prose:
            excerpt = " ".join(text.split())[:180]
            if "`" in text:
                problems.append("Literal backtick in rendered prose: " + excerpt)
            if DIAGRAM.search(text):
                problems.append("Diagram source in rendered prose: " + excerpt)
            if COMMAND.search(text):
                problems.append("Executable command outside a code element: " + excerpt)
        return problems


def check_markdown(text: str) -> tuple[list[str], str, list]:
    tokens = MARKDOWN.parse(text)
    rendered = MARKDOWN.renderer.render(tokens, MARKDOWN.options, {})
    surface = Surface()
    surface.feed(rendered)
    problems = surface.problems()
    lines = text.splitlines()
    fences = [token for token in tokens if token.type == "fence"]
    for token in fences:
        start, stop = token.map
        closing = re.sub(r"^(?:[ \t]*>[ \t]?)+", "", lines[stop - 1]).strip() if stop > start + 1 else ""
        pattern = re.escape(token.markup[0]) + "{" + str(len(token.markup)) + ",}"
        if not re.fullmatch(pattern, closing):
            problems.append(f"Unclosed code fence at source line {start + 1}")
    return problems, rendered, fences


def check_native(text: str) -> dict:
    surface = Surface()
    surface.feed(text)
    problems = surface.problems()
    required = ("flowchart LR", "adb install -r", "./gradlew :app:assembleDebug", "./gradlew :app:testDebugUnitTest :app:lintDebug")
    for command in required:
        matches = [block for block in surface.blocks if command in block["text"]]
        if not matches:
            problems.append("Missing native GitHub code block: " + command)
        elif command != "flowchart LR" and not all(block["collapsed"] for block in matches):
            problems.append("README developer command is not inside details: " + command)
    return {"status": "FAIL" if problems else "PASS", "code_blocks": len(surface.blocks), "details": surface.details, "issues": problems}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path, default=Path("documentation-render-results"))
    parser.add_argument("--native-readme", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.native_readme:
        result = check_native(args.native_readme.read_text(encoding="utf-8"))
        (args.output / "github-render-report.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, indent=2))
        return int(bool(result["issues"]))

    root = args.root.resolve()
    names = subprocess.check_output(["git", "ls-files", "-z", "--", "*.md", "*.markdown", "*.html"], cwd=root).decode().split("\0")
    records, diagrams = [], []
    for name in sorted(filter(None, names)):
        source = root / name
        if source.is_symlink() or not source.resolve().is_relative_to(root):
            raise ValueError("Documentation path must be a regular repository file: " + name)
        text = source.read_text(encoding="utf-8-sig")
        if source.suffix == ".html":
            surface = Surface()
            surface.feed(text)
            problems, rendered, fences = surface.problems(), text, []
        else:
            problems, rendered, fences = check_markdown(text)
        record = {"path": name, "sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "code_fences": len(fences), "issues": problems}
        records.append(record)
        snapshot = args.output / "sources" / name
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        snapshot.write_bytes(source.read_bytes())
        preview = args.output / "previews" / (name + ".html")
        preview.parent.mkdir(parents=True, exist_ok=True)
        preview.write_text("<!doctype html><html><head><meta charset=\"utf-8\"><title>" + html.escape(name) + "</title></head><body>" + rendered + "</body></html>", encoding="utf-8")
        for token in fences:
            if token.info.strip().split(" ")[0] == "mermaid":
                filename = f"diagram-{len(diagrams) + 1}.mmd"
                (args.output / filename).write_text(token.content, encoding="utf-8")
                diagrams.append({"source": name, "line": token.map[0] + 1, "file": filename})
        for problem in problems:
            print(name + ": " + problem)
    result = {"status": "FAIL" if any(record["issues"] for record in records) else "PASS", "files_checked": len(records), "markdown_files": sum(r["path"].endswith((".md", ".markdown")) for r in records), "html_files": sum(r["path"].endswith(".html") for r in records), "code_fences": sum(r["code_fences"] for r in records), "issue_count": sum(len(r["issues"]) for r in records), "diagrams": diagrams, "files": records}
    (args.output / "report.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key != "files"}, indent=2))
    return int(result["status"] != "PASS")


if __name__ == "__main__":
    raise SystemExit(main())
