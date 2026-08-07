# iOS multicast entitlement (for full-fidelity DLNA discovery)

Multicast SSDP on iOS does not work until Apple grants this app the **Multicast Networking**
entitlement: `sendto()` to `239.255.255.250` fails with `EPERM`, joining the group for `NOTIFY`
announcements fails the same way, and nothing throws or prompts — the failure shape `Info.plist`
already documents for the Cast SDK.

`DlnaTransport.swift` no longer depends on it for basic discovery: when every multicast send is
refused, `performSearch()` falls back to UPnP 1.1 **unicast** M-SEARCH, swept across the Wi-Fi
subnet. Unicast UDP to LAN hosts needs no entitlement — only the Local Network permission the
Cast side already prompts for — so mainstream renderers on a home /24 are found without Apple's
sign-off. The entitlement is still worth requesting, because the fallback is strictly worse
than the real thing:

- devices are only found by the sweep if they implement unicast search, which some older
  renderers do not;
- passive `NOTIFY` listening stays dead, so a TV switched on mid-session only appears at the
  next 10-second search cycle rather than announcing itself;
- the sweep is capped near a /24, so renderers on large or segmented subnets stay invisible.

## Why the existing keys are not enough

`iosApp/iosApp/Info.plist` already declares:

- `NSLocalNetworkUsageDescription` — triggers the "find and connect to devices on your local
  network" prompt.
- `NSBonjourServices` — `_googlecast._tcp` and `_CC1AD845._googlecast._tcp`.

Those cover the Cast SDK, which discovers over Bonjour/mDNS. They do **not** cover SSDP. Since
iOS 14, sending to or joining an arbitrary IP multicast group is gated behind a separate,
Apple-approved entitlement that is not granted automatically and cannot be self-signed for
App Store or TestFlight builds.

## What to request

Submit the **Multicast Networking Entitlement** request form:
<https://developer.apple.com/contact/request/networking-multicast>

Requested entitlement key: `com.apple.developer.networking.multicast`

Suggested justification — this is what Apple's reviewers look for, a concrete protocol and a
user-visible feature rather than a general "we need networking":

> Nuvio is a video player. It lets users play a video on a television on the same Wi-Fi network.
> Older televisions that do not support Chromecast still support DLNA/UPnP-AV, which is
> discovered with SSDP — an M-SEARCH request sent by UDP to the standard SSDP multicast group
> 239.255.255.250:1900, and NOTIFY announcements received from that same group. There is no
> unicast or Bonjour alternative for SSDP; the protocol is defined in terms of that multicast
> group. The app only sends the standard SSDP M-SEARCH and only listens for SSDP responses and
> announcements. Multicast is used solely while the user has the "Cast to" device picker open,
> and stops when it is dismissed.

Apple typically responds within a few weeks. The entitlement is bound to a specific App ID, so
request it for the production bundle identifier.

## After it is granted

1. Enable the capability for the App ID in the Developer portal, then regenerate the
   provisioning profiles used by `build-ipa.yml`.
2. Add an entitlements file (there is currently no `.entitlements` in `iosApp/` at all) —
   `iosApp/iosApp/iosApp.entitlements`:

   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
   <plist version="1.0">
   <dict>
       <key>com.apple.developer.networking.multicast</key>
       <true/>
   </dict>
   </plist>
   ```

3. Point `CODE_SIGN_ENTITLEMENTS` at it in the Xcode target's build settings.
4. Widen `NSLocalNetworkUsageDescription`, which currently names only Chromecast:

   > Nuvio uses your local network to find Chromecast and DLNA devices to play video on.

## Verifying

The entitlement cannot be tested in the simulator — it needs a real device on a real Wi-Fi
network with a DLNA renderer present (VLC's "Stream to DLNA", or any TV with DLNA enabled).

A quick negative check that does not need a TV: with the entitlement missing, the `sendto()` in
`performSearch()` returns `-1` with `errno` set (typically `EHOSTUNREACH` or `EPERM`). With it
granted, `sendto()` succeeds and responses arrive. That single return value distinguishes "the
entitlement is missing" from "the code is wrong", which is otherwise very hard to tell apart
given both look like an empty device list.

## Scope note

This affects **iOS only**. Android DLNA discovery needs no equivalent — it uses
`CHANGE_WIFI_MULTICAST_STATE` plus a `WifiManager.MulticastLock`, both already in place
(`composeApp/src/androidMain/AndroidManifest.xml`, merged into the app manifest through the
`:composeApp` dependency).
