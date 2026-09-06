# Below the connect button: building an Android network engine

**By Sp2ctr2 · Tunnel HTTPS**

A VPN indicator does not tell you where your traffic goes. In Tunnel HTTPS, Android's VPN interface is the entry point to a local packet-processing engine—not a connection to a developer-operated VPN server.

The project is open for inspection and focused technical feedback. Start with the [repository](https://github.com/Sp2ctr2/Tunnel-HTTPS), the [architecture](../release/01_ARCHITECTURE_AND_VPN_FLOW.md), or the [release page](https://github.com/Sp2ctr2/Tunnel-HTTPS/releases). Release-specific installation instructions and verification files belong to the release, not an unofficial mirror.

## The question behind the project

What can an Android application do with network policy before traffic leaves the device, without operating a remote full-traffic gateway?

Tunnel HTTPS explores that question below the UI: packet normalization, local DNS policy, bounded transport relays, TLS ClientHello strategies, and learning from connection outcomes. Its application interface is deliberately much simpler than the implementation behind it.

This is not a replacement for a remote VPN. It does not change the public IP address or country, decrypt HTTPS content, or promise anonymous browsing. DNS resolvers and destination services still receive the requests sent to them.

## The implementation boundary is the interesting part

Android provides VpnService and the TUN interface. The repository receives the selected traffic and applies its own local packet and connection policies. Protected upstream sockets then use Android/Linux networking to reach external services.

The distinction is important: **local TCP state is not the same thing as reimplementing the entire Internet-facing kernel TCP stack**.

| Concern | Repository responsibility | Read the implementation |
| --- | --- | --- |
| Packets | IPv4/IPv6 validation, normalization and bounded fragment handling | [IPv6 normalizer](../../app/src/main/java/com/tunnelvpn/app/Ipv6PacketNormalizer.kt) |
| DNS | Message parsing, response validation, cache policy and resolver strategy | [DNS validator](../../app/src/main/java/com/tunnelvpn/app/DnsMessageValidator.kt) |
| Transport | Local TCP/UDP relay state, cleanup and resource budgets | [TCP relay](../../app/src/main/java/com/tunnelvpn/app/TurboTcpForwarder.kt) |
| Handshakes | TLS ClientHello parsing and eligible fragmentation strategies | [ClientHello parser](../../app/src/main/java/com/tunnelvpn/app/TlsClientHello.kt) |
| Adaptation | Local contextual strategy selection with a separate neural shadow policy | [Turbo](../../app/src/main/java/com/tunnelvpn/app/TurboAiEngine.kt) / [Aegis](../../app/src/main/java/com/tunnelvpn/app/AegisLocal2Engine.kt) |

The architecture and source links above describe the implementation. They are not claims of universal protocol conformance, independent security certification, or measured improvements on every network.

## Why bounded behavior matters

Once an application participates in a packet path, the failure cases matter as much as the happy path. Malformed input, incomplete fragments, stale connections and unbounded queues cannot be treated as cosmetic bugs.

The repository's engineering documents describe explicit budgets and rejection paths. Review the [network security and data-flow notes](../release/03_NETWORK_SECURITY_AND_DATA_FLOW.md) alongside the [tests](../../app/src/test/java/com/tunnelvpn/app/). A useful review challenges a specific parser, state transition or resource boundary with a reproducible case.

## Learning is not a license to hand over control

Turbo's contextual learner selects eligible connection strategies. Aegis is a separate experimental neural policy operating in shadow mode: its presence should not be described as unvalidated neural control of all live routing.

That separation is a design decision, not a marketing footnote. The [Turbo architecture](../TURBO_AI_ARCHITECTURE.md) and the current implementation show where selection, observations and safety boundaries actually sit.

## What would make the project better?

The most useful contributions are specific: a minimized synthetic packet that exposes a parser mistake; a reproducible TCP relay regression; a clear distinction between DNS validation and resolver trust; or an Android lifecycle issue with the device class, OS version, build and steps recorded.

Please separate JVM test results, emulator checks, physical-device observations and carrier behavior. One does not prove the others. Dated reports in the repository are snapshots, not permanent guarantees.

If you try a published beta, use a non-critical device or network first. Do not uninstall an existing build without understanding the effect on its local data. Report a connectivity problem through [Issues](https://github.com/Sp2ctr2/Tunnel-HTTPS/issues); report an exploitable vulnerability using [SECURITY.md](../../SECURITY.md), without putting private browsing history or credentials in a public thread.

## Open the code, not just the feature list

The project is Apache-2.0 licensed and includes the app, networking implementation, tests and engineering notes. Its strongest introduction is the implementation itself.

**[Explore Tunnel HTTPS](https://github.com/Sp2ctr2/Tunnel-HTTPS)** · **[Read the contribution guide](../../CONTRIBUTING.md)** · **[한국어 소개](README.ko.md)**

A star is welcome when the project is useful or worth following. Reproductions, careful reviews and honest compatibility reports are even more useful. Please do not organize voting campaigns, buy engagement, or promote unverified claims on the project's behalf.
