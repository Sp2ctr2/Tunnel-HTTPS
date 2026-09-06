# Reviewer instructions

Account required: no account exists in repository behavior. Test credentials: not applicable.

1. Install the signed internal-test build and launch Tunnel HTTPS.
2. Review and test both decline and accept paths of the prominent disclosure once implemented.
3. Tap the main connect control, then approve Android’s VPN permission.
4. Confirm the system VPN indicator and ongoing notification.
5. Browse a normal HTTPS page and review local diagnostics without expecting HTTPS decryption.
6. Enable/disable DoH, ad blocking, app bypass, browser-only, and Turbo one at a time; reconnect where the existing flow requests it.
7. For split-domain testing use a reviewer-controlled non-sensitive domain and verify the documented local relay path.
8. Disconnect from both the app and notification stop action.

Prerequisites: ordinary Internet access. IPv6-only/NAT64 and provider-blocked regions need separate disclosed test networks. Debug-only behavior must not be shown. Do not describe the app as a remote VPN.
