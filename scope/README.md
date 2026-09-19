# Tunnel Scope

An offline 3D code landscape containing the actual Tunnel-HTTPS repository snapshot. This implementation is isolated on `scope-visualizer-20260919`; the Android application, main branch, and existing Pages deployment are unchanged.

## Run

The built `Tunnel_Scope.html` is self-contained: renderer, controls, UI, index, and all UTF-8 source contents are inline. Save it and open it in a WebGL2-capable desktop browser. It needs no CDN, server, API key, login, or runtime network connection. A blocked chat attachment preview is not a desktop browser.

`Tunnel-Scope-linux-x86_64` is a compiled GTK 3 / WebKitGTK 4.1 host with the complete viewer and source snapshot embedded as a GResource. It is not a separate Rust renderer. It requires those system runtime libraries. Run `chmod +x Tunnel-Scope-linux-x86_64` and `./Tunnel-Scope-linux-x86_64`. Install a Korean-capable system font if Korean text displays as boxes; fonts are not redistributed in the package.

Arch Linux runtime packages: `gtk3`, `webkit2gtk-4.1`; optional CJK coverage: `noto-fonts-cjk`. Build packages also require a C compiler, pkg-config, and GLib tools. The distributed native build was compiled and executed on Ubuntu 24.04 x86_64; target-user GPU performance has not been benchmarked.

## Source provenance

- Original repository: Sp2ctr2/Tunnel-HTTPS
- Pinned commit: `9b099be86f4fb00a5c506bc2d6d9d7be8a79f67d`
- Commit timestamp: `2026-09-13T12:54:04Z`
- 263 files, including 261 exact UTF-8 source texts and two binary metadata entries
- 3,167,655 bytes; 55,590 physical text lines; 50,677 nonblank lines
- 1,864 consecutive source pages of at most 32 lines
- 2,920 heuristic declaration matches, not full AST or call-graph analysis

Physical line totals include documentation, configuration, tests, comments, and blank lines. They are not executable SLOC. Every embedded text was checked against its original Git blob SHA-1. Binary bytes are in the original source ZIP, not in the HTML. The pinned snapshot does not silently follow later changes to main.

## Controls and features

Wheel: zoom. Left drag: pan. Right drag: orbit. Double-click: focus a source page. Enter: original source drawer. F: frame selection. H: whole repository. Ctrl/Cmd+P or K: search. Ctrl/Cmd+Shift+F: full-text search. Symbols tab: declarations. V: 2D/3D. L: labels. Z: clean canvas. Space: guided tour. Esc: close/back.

The viewer includes an actual directory tree, file inspector and hashes, file/symbol/full-text search, virtualized source viewing with syntax colors and line navigation, copy/download of complete original source, minimap, camera flights, PNG export, and actual canvas video recording on browsers supporting MediaRecorder. A tour can be recorded. Recording is capped at 120 seconds; unsupported codecs are reported instead of simulating recording.

Area can represent physical lines, bytes, or equal file sizes. Color represents actual directory group, language, or size. Height is derived from log file length and page nonblank density; no invented complexity or risk score is shown. Small line marks derive from actual indentation and line length. Close zoom loads real syntax-colored source textures, capped at 28 visible pages and 42 cached textures. Rendering is on demand and idle state is reported honestly.

Local-folder import reads only selected local files and never uploads them. Common build/dependency directories are excluded. Import limits are 200 MiB per selection and 32 MiB per text file. Imported text is never executed. Export HTML embeds the newly selected codebase for subsequent offline reopening. Sharing such an HTML shares its source contents too.

## Build

The branch workflow `.github/workflows/scope-build.yml` exports the pinned Git snapshot without executing application code, locally bundles Three.js r170 with OrbitControls, produces the standalone HTML, compiles the native host, executes a native WebGL/original-source self-test, and uploads the outputs as `tunnel-scope-build`.

With `snapshot.json` and the locally bundled `vendor.js` from the build artifact or complete package:

```sh
python3 scope/build.py --snapshot snapshot.json --vendor scope/vendor.js --out Tunnel_Scope.html
bash scope/native/build.sh Tunnel_Scope.html Tunnel-Scope-linux-x86_64
```

For another local working tree:

```sh
python3 scope/build.py --repo /path/to/repository --vendor scope/vendor.js --out My_Scope.html
```

Python 3.10+ is required. A Git working-tree import reads current tracked worktree contents, not an asserted pristine HEAD. The builder does not execute those sources.

## Verified results

The delivered HTML passed 33 browser interaction checks on Chromium/Linux/Xvfb/software WebGL2, with zero observed network requests and zero JavaScript runtime errors. Checks covered actual source navigation, source-byte-preserving download, source LOD, file/symbol/full-text search, area/color controls, 2D/3D, clean mode, PNG, actual video output, tour, offline export/reopen, 390px mobile layout, local import, non-execution of imported HTML, and re-export.

Four Python integrity tests passed. Node layout/index checks passed for all files and all three area metrics, with source-page continuity and HTML escaping verified. Test source and JSON reports are included in the complete downloadable project package.

The native CI self-test passed with `ready=true`, `fallback=false`, `renderer=WebGL 2.0`, seven active source textures, and exact DnsCache source matching. These results are functional checks, not a guarantee of 120fps, all-platform compatibility, or absence of all possible defects.

## Licensing

Original Tunnel-HTTPS source and new viewer implementation: Apache License 2.0; retain the repository LICENSE. Three.js and OrbitControls: MIT; retain `THREE-LICENSE.txt`. GTK/WebKitGTK are system libraries and are not bundled. No fonts are redistributed.
