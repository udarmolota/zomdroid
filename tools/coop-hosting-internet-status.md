# Internet hosting changes — 2026-09-13

Implemented after Inna approved the tasks in coop-hosting-internet-brief.md. No APK, commit or push.

## Changes
- Reports include coop-probe/coop-console.txt, server-exception.txt, hosting-internet.properties and coop JVM crash logs.
- CoopSlave fd 1 no longer receives the native ZMEM sampler; server samples use Android logging.
- The server agent observes the game's PortMapper.discover/addMapping return values. It performs no independent discovery or mapping. After a successful default UDP mapping it reads PortMapper.getExternalAddress().
- Status is atomically published to a per-session file for the host UI and to a report file. A new server launch resets report status. Only the configured default UDP port contributes; secondary/TCP mappings cannot replace it.
- A small status label appears at the top of the Android game window while hosting. Tap for port, router external address, phone WLAN address and copyable instructions. States: checking, discovered, mapped, failed, no gateway, unavailable JNI, unknown. A server that starts without a mapping result is shown as unknown, not successful.
- Text distinguishes a router accepting a mapping from proven external connectivity. Non-public WAN addresses warn about upstream NAT without claiming provider CGNAT is proven. UI strings are English/Russian; other locales fall back to English.
- Client and server use the same per-instance macOS environment selection helper. Server PathFind options target its actual coop-probe/server-probe cachedir. Client hosting/probe options likewise target their actual cachedir. No env contract changes or box64 tuning changes.

## Validation
- Gradle compileDebugJavaWithJavac, processDebugResources and externalNativeBuildDebug passed.
- testCoopAgent passed (existing process bridge and protocol tests).
- testCoopInternet passed: transformed UPnP return values, custom server port, ignored secondary/TCP mapping, missing external address, B41 JNI guard, report/session publication and stdout isolation. These are controlled JVM tests, not successful router mapping from Android.
- Existing read-only tools/upnp-probe.py was run on this LAN: gateway 192.168.1.1 (Technicolor DGA2232) exposed WPS but no IGD port-mapping service. No mappings were added/removed.

## Remaining device check
Run updated code from Studio, host a world, tap the status label and export its report. Verify coop-console.txt contains the game's UPnP outcome and server-native.log shows the requested macOS libraries actually loaded in the server process.

Successful miniupnpc mapping from :server remains unverified until an IGD-enabled router is available. On that network, inspect the UDP mapping to the host phone, then connect from mobile data using the router's external address and the configured port. Background hosting/foreground service remains deferred.
