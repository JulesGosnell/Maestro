# iOS Device Config

A wrapper around `simctl` and XCTest to communicate with iOS devices.

## Prerequisites

### Xcode

Install the latest Xcode (Command Line Tools are not enough, install the full IDE).

### IntelliJ setup

If you are working with this subproject, update your IntelliJ config (Help -> Edit Custom Properties) by including the following lines:

```
# Needed for working with idb.proto definition
idea.max.intellisense.filesize=4000
```

Then restart the IDE.

## Host-side touch for simulators (smithr#1313)

`-Dmaestro.iosHostTouch=on|off`, **default off**. With it on, tap, double tap, long press and
swipe/scroll on an iOS **simulator** go host-side through the simulator's own built-in digitizer
(`ios-host-touch`, built from `src/main/objc/ios-host-touch.m` into `maestro-ios.jar` when Maestro
is built on macOS), instead of XCTest's `_XCT_synthesizeEvent`. XCTest makes a new virtual
digitizer for every gesture, and under CPU load backboardd fails to classify it in time and drops
the gesture while XCTest reports success. Everything else — element lookup, hierarchy, text input,
launch, screenshots — stays on XCTest. A **physical iPhone is never affected**, whatever the flag says.

Flip it per run, no rebuild:

```bash
maestro --udid <sim> test flow.yaml                                     # off (the default)
MAESTRO_OPTS="-Dmaestro.iosHostTouch=on" maestro --udid <sim> test flow.yaml
# artha, through smithr's sidecar (forwards MAESTRO_JVM_OPTS to every Maestro JVM):
MAESTRO_JVM_OPTS="-Dmaestro.iosHostTouch=on" ./bin/e2e-cell-run.sh all --platform ios --substrate simulated ...
```

What the log says (the driver log, `maestro.log`):

- once per session: `[HostTouch] mode=on (source: -Dmaestro.iosHostTouch=on); ...helper ready`, or
  `[HostTouch] mode=off (source: ...)`. Any value other than exactly `on` or `off` is **off**, logged
  as `... is neither on nor off, so OFF`.
- per gesture: `[HostTouch] tap (x, y) hold=100ms path=host down->up=100.0ms ...`, or
  `path=xctest reason=...` when the host path could not take that gesture before sending anything
  (point off screen, XCTest reporting a different screen size than the simulator's).
- With the flag on, a helper that does not start **fails the session** with a message naming the
  flag; it never falls back to XCTest silently. A helper that fails after sending a gesture does
  not re-send it (it may have landed), logs `[DriverIssues]`, and later gestures use XCTest.

`-Dmaestro.iosHostTouch.helper=<path>` runs a different helper binary (development only).

⛔ **The transport is version-pinned.** The helper speaks SimulatorKit's *legacy Indigo* input
(`IndigoHIDMessageForMouseNSEvent`, 6 arguments), correct for Xcode 26.2 / CoreSimulator 1051.17.7.
idb records that from CoreSimulator 1155.4 (Xcode 27) the guest drops legacy Indigo input — the
successor is the DTUHID transport (idb `SimulatorDigitizerHIDTransport.swift`). A transport that
ACCEPTS a touch and then drops it passes the helper's start check and acks, so nothing here fails
loud. The mode line names the CoreSimulator version for that reason. **After any Xcode or
CoreSimulator upgrade, re-run the starvation acceptance (smithr#1313 item 1: host path 0 drops in
≥300 taps under testmanagerd SIGSTOP 50/3 ms) before turning the flag on.**
