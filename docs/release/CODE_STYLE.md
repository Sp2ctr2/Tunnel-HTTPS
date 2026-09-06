# Tunnel HTTPS code style

- Name classes for their actual packet or lifecycle role; keep public/JS compatibility aliases only for real callers.
- Boolean names start with `is`, `has`, `can`, or `should` when compatibility permits.
- Include units in time and ambiguous size names.
- Keep VPN/DNS/MTU/TCP/UDP/TLS/SNI/QUIC/DoH acronyms.
- Preserve coroutine cancellation and close lifecycle-owned dispatchers/executors.
- Use monotonic time for elapsed duration and TTL decisions.
- Avoid allocation and collection construction in packet hot paths.
- Bound bridge, Intent, cache, queue, and flow inputs; reject invalid input explicitly.
- Diagnostics report observed state, never intended routing.
- Never log packet payloads, domains, installed-app lists, URLs, credentials, or tokens.
- Comments explain Android/protocol constraints and failure policy, not visible code.
- Do not create abstractions for hypothetical implementations or shorten protocol code without tests.
