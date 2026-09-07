# White product site, refined

The user-approved reference is the white product page at commit `8738f3a7`, not the later framed workspace. The public website now builds from `website/product/refine.py`. The baseline product builder and styles supply the original hierarchy, terminology, implementation boundaries and contributor tools. The refinement removes the phone preview and long stacked home sections, improves spacing, typography and component detail, and adds five in-place, addressable scenes.

There is no desktop-window frame, gray dashboard surround, decorative geometry, WebGL renderer or fake network measurement. The white canvas, large product headline and black primary action are intentionally preserved. The illustrated DNS/filtering/local-learning exhibit uses text and semantic controls, not a screenshot of a fake native app. It makes no network requests.

`scenes.js` keeps the product header stationary, coordinates interruptible View Transitions with a Web Animations fallback, supports browser history, ordinary link/modifier behavior, language links, mobile menu, keyboard controls and reduced motion. Long technical or installation content may scroll inside the active scene. It is never clipped to satisfy a screenshot.

## Maintained sources

- `website/product/build.py`, `style.css`, `app.js`, `boot.js`: the approved product baseline and its local tools.
- `website/product/refine.py`: combines the original content into ten English/Korean routes.
- `website/product/refinement.css`: the product-page refinement, not a dashboard theme.
- `website/product/scenes.js`: scene navigation and clearly labeled feature illustrations.
- `website/product/test_refinement.py`: responsive, interaction, accessibility and publication checks.

Build with `python3 website/product/refine.py --output _site --resolve-release`. Test with `python3 website/product/test_refinement.py --root _site --out white-review`. CI injects axe through the testing protocol; the deployed Content Security Policy remains enforced. All CSS/JS filenames are content-hashed. Public-file verification compares ordinary URLs with the exact tested artifact.

Archived `website/workspace/` sources do not build or deploy the public site. Android code, releases, signing keys and application behavior are unchanged.
