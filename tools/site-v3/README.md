# Interactive website refresh

This directory contains the source and browser verification for the Tunnel HTTPS public website.

The refreshed public surface is intentionally separate from the Android packet engine: it does not capture traffic, issue DNS queries or make application-network requests. Protocol walkthroughs are explicitly labelled architectural illustrations.

Quality gates cover real route navigation, keyboard interaction, small-screen reflow, theme persistence, local APK checksum comparison, no-JavaScript installation instructions and Content Security Policy boundaries.

The Android application, package identity and signing keys are not modified by the website refresh.
