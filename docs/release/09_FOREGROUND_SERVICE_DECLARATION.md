# Foreground service declaration draft

- Feature: active local VPN packet processing.
- Type: `specialUse` / subtype `vpn`.
- Why immediate: the TUN and packet relays must run continuously after the user connects; delaying execution breaks connectivity for captured traffic.
- User impact if interrupted: captured connections and DNS resolution stop; Android tears down the VPN.
- Initiation: user taps connect or explicitly enables auto-start; Android VPN permission is required.
- Visibility: ongoing low-importance notification titled Tunnel HTTPS with a stop action.
- Repository evidence: `AndroidManifest.xml`, `TunnelVpnService.startForegroundCompat()`, notification channel and stop action.

Video shot list: launch; show prominent disclosure/decline/accept once implemented; grant Android VPN permission; connect; show system VPN indicator and notification; browse; stop from notification; demonstrate auto-start only if Play reviewer requests it.

Official special-use requirements: [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
