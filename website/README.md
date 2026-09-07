# Tunnel HTTPS website

The maintained site is **`website/workspace/`**. Its five tabs replace the central pane while the shell, navigation and footer remain stationary. English and Korean each have real static addresses, including the existing engineering, privacy and guide routes.

See [the workspace documentation](workspace/README.md) for the design, interaction model, build commands and verification scope.

`website/product/` is retained as the previous product-first design. It is **not** the source deployed by the Pages workflow. Do not edit legacy `docs/index.html` or the previous product module to change the public site.

The single `.github/workflows/pages.yml` builds and tests the workspace, deploys the exact tested artifact from `main`, and verifies ordinary public URLs and interactions afterward. Branch and pull-request builds do not deploy. Screenshots, transition recordings and test reports are preserved as workflow artifacts.

This site change does not modify Android source, package configuration, APK releases or signing material. The website's local tools are not evidence of Android runtime or real-network performance.
