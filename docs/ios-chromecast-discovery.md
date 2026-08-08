# iOS Chromecast discovery — every way the device list can stay empty

## The one that actually happened

Discovery was broken from the moment the Cast SDK was added, and none of the usual suspects
below were responsible. `GoogleCast.framework` is a **static** library, and Xcode does not copy
resources out of static frameworks — CocoaPods does, which is why Google's own sample works and
this hand-integrated xcframework did not. The app's Copy Bundle Resources phase was empty and
the project contained no `.bundle` reference at all, so the SDK shipped without any of its seven
resource bundles. At launch it said so, once, and then went quiet:

```
CoreData model CastFrameworkDB.momd not found at (null)
Can't initialize database because the model can't be found in bundle, aborting
```

After that, `startDiscovery` produced no SDK output whatsoever, even at
`GCKLoggerLevelVerbose` — not a failed scan, no scan at all — while a plain `NWBrowser` on the
same service found the receiver immediately. That gap between "the OS sees it" and "the SDK
says nothing" is the signature of this bug, and it is worth checking first, because every
symptom below looks identical from the outside.

The fix is the `Copy Google Cast Resources` build phase in `build-ipa.yml`'s Xcode project,
which copies every `*.bundle` from the slice matching the platform being built. If Cast ever
goes silent again after an SDK upgrade, confirm those bundles are still in the built `.app`
before investigating anything else.



Chromecast discovery on iOS fails *silently* by design: when any layer blocks it, nothing
throws, no callback fires with an error, and the picker just never lists a device. This is the
complete map of those layers, ordered from "in this repo" to "in the user's hands", with the
quickest test for each. The companion problem for DLNA is documented in
`ios-multicast-entitlement.md` — the two protocols fail for different reasons and the fixes do
not overlap.

## How discovery works here, and why no entitlement is involved

The vendored Cast SDK (4.8.3, `iosApp/Frameworks/GoogleCast.xcframework`) discovers receivers
over **Bonjour/mDNS through Apple's own APIs**. Since SDK 4.4.5 Google switched from raw
multicast sockets to `NSNetServiceBrowser` precisely so that the Apple-approval-gated
`com.apple.developer.networking.multicast` entitlement is **not** required. Do not add it for
Chromecast — it is only needed for the SSDP side of DLNA. What Bonjour browsing *does* require
since iOS 14 is:

- `NSLocalNetworkUsageDescription` — the reason string for the one-shot Local Network prompt.
- `NSBonjourServices` — an allowlist of the exact service types the app may browse. Ours are
  `_googlecast._tcp` plus `_CC1AD845._googlecast._tcp`; the second one encodes the receiver
  application id and **must change if `CastBridge.receiverApplicationID` ever stops being the
  default media receiver**. A service type missing from this list is silently unbrowsable.

Both keys are in `iosApp/iosApp/Info.plist`, which is the plist the target actually builds
(`INFOPLIST_FILE = iosApp/Info.plist`). The static SDK's other needs — the `-ObjC` linker flag,
the device (`ios-arm64`) slice, `Network`/`Security` frameworks — are all in place in
`project.pbxproj`.

## The two app-level bugs this branch fixes

1. **Discovery was auto-starting at app launch.** `CastBridge.install()` set
   `startDiscoveryAfterFirstTapOnCastButton = false`, believing that made discovery manual. It
   does the opposite: that flag defers to `disableDiscoveryAutostart`, whose default (`false`)
   starts browsing the moment `GCKCastContext` is created — during `iOSApp.init()`. So iOS's
   **one-shot** Local Network prompt appeared over the launch screen, with no visible reason to
   say yes. A user who taps "Don't Allow" there has killed discovery permanently: every later
   browse returns empty lists with no error and iOS never asks again. The fix sets
   `disableDiscoveryAutostart = true` as well, so browsing — and therefore the prompt — happens
   only while the device picker is open, where the request explains itself.

2. **The browse that triggers the permission prompt dies even when the user allows it.** The
   first-ever `startDiscovery()` is what makes iOS show the alert, and the Bonjour browse behind
   it has already been refused by the time the user taps Allow. It does not recover on its own,
   so the first picker-open finds nothing even after granting — classically "it worked the
   second time I opened it". The alert deactivates the app, so `CastBridge` now restarts the
   browse on `didBecomeActive` while discovery is on. The same hook catches the user returning
   from Settings after flipping the Local Network toggle.

## Apple-side gates on the user's phone

These cannot be fixed in code; the picker now shows a hint after ~12 seconds of empty results
pointing at the first of them.

- **Local Network permission is denied.** Settings → Privacy & Security → Local Network →
  Nuvio must be on. There is **no API to read this permission** — a denial is indistinguishable
  from an empty network — and the prompt is shown once per install, ever. If the app is missing
  from that list entirely, iOS hasn't registered the first browse attempt yet; open the picker
  once, then check again.
- **iOS 18's Local Network bugs.** Several 18.x releases shipped bugs where the prompt never
  appears (the app is treated as denied), or where flipping the toggle doesn't take effect
  until the phone is **rebooted**. Dev-signed and sideloaded apps hit this more often than App
  Store ones. Toggle off, toggle on, reboot — in that order — before blaming anything else.
- **Sideload re-signing.** The permission grant is tied to the app's identity. AltStore/
  SideStore-style installs that assign a per-account bundle id, or a re-install under a new
  free-account certificate that changes the id, produce what iOS considers a brand-new app: the
  old grant is gone and the prompt must appear again. If it doesn't, see the previous bullet.
- **The Simulator.** Local Network enforcement and mDNS behave differently under the Simulator
  and Google does not support Cast discovery there. Only a physical device is evidence.
- **The phone is not on Wi-Fi.** Cellular-only (or Wi-Fi off with the phone on the TV's subnet
  via some other path) yields nothing. A VPN or DNS-filter profile (`NEPacketTunnel` apps —
  including many ad blockers) frequently swallows multicast; disable it while testing. iCloud
  Private Relay and Private Wi-Fi Address are *not* culprits — they don't affect LAN mDNS.
- **Screen Time / MDM restrictions** can deny Local Network per-app on managed devices.

## Network-side gates (same silent symptom, nothing to do with Apple)

- **Guest SSID / AP isolation** — client-to-client traffic, including mDNS, is blocked by
  design. Hotel and office networks almost always behave this way.
- **mDNS not crossing network segments** — phone on 5 GHz, Chromecast on 2.4 GHz with the
  router not bridging multicast between bands; mesh nodes or VLANs without an mDNS reflector;
  aggressive IGMP-snooping switches that prune the multicast group.
- **The phone and the Chromecast are simply on different networks** — the single most common
  real-world cause. Verify the SSID on both ends, not from memory.

## Receiver-side gates

- The Chromecast is asleep in a low-power state and not answering queries — power-cycle it.
- Very old Cast firmware, or a Google TV device with "network standby" disabled.

## Fastest way to bisect the stack

1. **Another cast-capable app on the same iPhone** (YouTube, Google Home): finds the device →
   phone, permissions, network, and receiver are all fine; the problem is in this app. Finds
   nothing → stop reading app code, it's the phone or the network.
2. **From a Mac on the same Wi-Fi:** `dns-sd -B _googlecast._tcp` lists every advertising
   Chromecast. Empty here → network or receiver, not iOS at all.
3. **Console.app** attached to the device, filtering on `GCKDiscoveryManager` or `mDNS`:
   permission denials appear as `NoAuth`-style refusals on the browse, which is the only
   externally visible difference between "denied" and "nothing out there".
