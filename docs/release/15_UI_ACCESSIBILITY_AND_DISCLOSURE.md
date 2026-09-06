# UI, accessibility, and disclosure audit

No UI design, visible wording, navigation, animation, or user flow was changed.

Confirmed: connected/connecting/disconnecting/error states are represented; stop waits for the service’s final state broadcast; VPN permission rejection is handled; notification has a stop action; WebView content is local and external resource/navigation policy is bounded.

Release gaps requiring device/manual work:

- Separate VpnService disclosure and affirmative consent are absent.
- Existing DoH fallback wording conflicts with fail-closed runtime behavior.
- Screen reader order, content descriptions inside WebView, TalkBack labels, keyboard navigation, 200% text scaling, contrast, RTL, rotation, and process restoration were not executed on a device.
- Notification-permission denial behavior and Android 13–17 UI differences require device verification.
- Turbo “active” must be checked against the runtime state after the underlying-network probe; it is not proof of the TUN path.

UI changes needed to close these gaps require owner wording/design approval and were deliberately not made under the current constraint.
