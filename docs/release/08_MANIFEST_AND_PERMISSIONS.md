# Manifest and permissions

| Declaration | Exact use | Status / Play impact |
|---|---|---|
| `INTERNET` | DoH, relay, reachability | required core network permission |
| `ACCESS_NETWORK_STATE` | connectivity callback/classification | required |
| `POST_NOTIFICATIONS` | foreground VPN notification on API 33+ | required user-visible operation; denial device test pending |
| `FOREGROUND_SERVICE` | VPN service | required |
| `FOREGROUND_SERVICE_SPECIAL_USE` | API 34+ special-use service | declared with subtype property; Play declaration required |
| `VIBRATE` | UI haptic feedback | low sensitivity; used |
| `RECEIVE_BOOT_COMPLETED` | optional auto-start | policy/lifecycle sensitive; device verification required |
| `BIND_VPN_SERVICE` | service component permission | present on non-exported service; release blocker if removed |

No storage, location, advertising ID, wake lock, `QUERY_ALL_PACKAGES`, or broad package permission is declared. Package visibility is limited to launcher and browsable HTTPS handlers. Browser-only fails safely if no allowed browser package can be applied.

Merged release manifest verification: `allowBackup=false`, cleartext false, activity exported only for launcher, VPN service exported false with permission, boot receiver exported for protected system broadcasts, AndroidX profile components carry their library permissions/export rules. Release `debuggable` and `testOnly` are absent/false.

Backup and device transfer exclude the entire root. Network security trusts system roots only and disallows cleartext.
