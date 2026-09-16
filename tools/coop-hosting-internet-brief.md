# Coop hosting over the internet: brief for Codex

2026-09-13. Written by Claude for Codex, who built the hosting bridge (`CoopHostBridge`,
`CoopServerService`, `ServerProbeLauncher`, `app/src/coopAgent`, see `tools/server-probe.md`).
Rules unchanged: no code change, APK, commit or push without Inna's explicit approval. Never
propose writing to TIS. Players must never have to type env vars.

## Where we stand

| Test | Result |
|---|---|
| Host on the Asus, second phone on the same Wi-Fi (Build 42.20) | works (Inna) |
| Host on the Asus, Samsung SM-S938B on home Wi-Fi with a VPN on (`tun0` 10.2.0.2) | "Connection failed" |

Both phones ran the same release APK (signed with the debug key, not debuggable).

## Evidence from the failed run (19:08 to 19:11)

- Host (Asus): the `:server` process reached `UI_ServerStatus_Started` and sent
  `server-address@127.0.0.1:16261`. The host's own client logged in and loaded the world
  (`ReceivePlayerConnect ... mtu=1492`).
- Client (Samsung): `Initialising RakNet...`, then nothing until the failure. The release build
  does not log the address it dialed, so we don't know whether the home's public IP or the
  Asus's LAN IP was typed.
- No UPnP line anywhere in the host logcat. **This proves nothing**, see finding 1.

## Finding 1: the coop server's own log is invisible

`GameServer.main` with `-coop` calls `CoopSlave.initStreams()`. It keeps the original
`System.out`/`System.err` as the CoopMaster channel, then points **both** at a plain file,
`<cachedir>/coop-console.txt`. `CoopMaster` passes `-cachedir=<client cachedir>`, and in hosting
mode `GameLauncher` gives the client `-cachedir=<instance>/coop-probe`. So every server
`DebugLog` line, the UPnP outcome included, goes only to:

```
<instance home>/coop-probe/coop-console.txt        (app-private storage)
```

- Not in logcat. From the `:server` process logcat only shows native log lines and fd-1
  traffic: CoopSlave protocol lines (`ping@ping`, statuses) and our native `[ZMEM]`.
- Not in the bug report. `InstallerService`'s export adds `server-probe/server-console.txt` and
  `client-probe/console.txt`, but not `coop-probe/coop-console.txt`.
- Not readable over adb. Release builds are not debuggable, so `run-as` fails.

Side effect worth fixing while there: our native `[ZMEM]` sampler prints into fd 1 of the server
process, which is the CoopMaster channel. The host client then logs
`[CoopMaster] Unknown message incoming from the slave server: [ZMEM] ...` every 30 s. Harmless
but noisy.

## Finding 2: the home router cannot open ports by UPnP

The game's own UPnP path in 42.20 `GameServer.main` (bytecode offsets 2035 to 2227):

- Gated only by the server option `UPnP`, whose default is `true`.
- `PortMapper.startup()`, `discover()`, then
  `addMapping(defaultPort, defaultPort, "PZ Server default port", "UDP", 86400, true)`.
- The `UDPPort` (16262) mapping runs only when `SteamUtils.isSteamModeEnabled()`, so a
  `-nosteam` host needs UDP 16261 only.
- Log lines: "Router detection/configuration starting.", then "Default port has been mapped
  successfully" or "Failed to map default port", or "No UPnP-enabled Internet gateway found".

A read-only probe from the PC on the same LAN (`tools/upnp-probe.py`, only Get* SOAP actions)
found:

- Gateway 192.168.1.1 is a Technicolor DGA2232 (MediaAccess). It answers SSDP only as a **WPS
  device** (`wps_device.xml`, `WFADevice:1`, `WFAWLANConfig:1`). No `InternetGatewayDevice`,
  `WANIPConnection` or `WANPPPConnection` answers, neither to multicast nor to unicast M-SEARCH.
  The only other responder is a Samsung TV.
- So `PortMapper.discover()` must fail and nothing gets mapped. A packet from outside to UDP
  16261 dies at the router. **This alone explains "Connection failed".**
- CGNAT is unlikely. `tracert` shows the provider hop right after 192.168.1.1 is a public address
  (176.230.246.244), not 100.64/10. Without IGD the router's WAN address can't be read remotely,
  so this is inferred, not proven.

## What her network needs (no code)

1. Turn UPnP on in the router, or forward UDP 16261 by hand to the host phone's LAN IP and pin
   that IP in the router's DHCP.
2. While hosting, run `python tools/upnp-probe.py` on the PC. With UPnP on it must list
   "PZ Server default port" UDP 16261 pointing at the phone, and print the external IP.
3. Join from the Samsung on mobile data or a VPN at `<external IP>:16261`. With a VPN on,
   dialing the LAN IP is not an outside test, and many VPNs block LAN traffic anyway.

## Proposed tasks (each waits for Inna's OK)

1. **Make `coop-console.txt` reachable.** Add it to the bug-report export next to
   `server-console.txt`. Optionally mirror it to logcat, without writing into the fd-1
   CoopMaster channel.
2. **Tell the player whether internet play will work.** After the server starts, report one of:
   port mapped plus the external IP ("friends outside your Wi-Fi join at <ip>:16261"), mapping
   failed, no UPnP router ("turn on UPnP or forward UDP 16261 to <phone LAN IP>"), or an external
   IP in 100.64/10 or a private range ("your provider shares your address, forwarding can't
   help"). The external IP is printed through `DebugType.DetailedInfo.trace`, which may be
   filtered, so reading `PortMapper.getExternalAddress()` through the coop agent is more reliable
   than parsing the file. Where to show it is Inna's call.
3. **Prove the game's miniupnpc works from the `:server` process** once a router with UPnP is
   available. SSDP replies are unicast, so no `MulticastLock` should be needed, but this is
   untested on Android.
4. **The server process ignores the per-instance macOS switches** (lower priority).
   `ServerProbeLauncher` sets no `ZOMDROID_MACHO_LIBS` or `ZOMDROID_MACHO_SKIP`, so the host's
   server runs PopMan and MapCollision through box64: its `[jni-bind]` lines at 19:10:21
   (pid 11950) carry no `(macho)` tag. In a hosted game the server does the population work, so
   this is where PopMan costs the host. Export the same variables `GameLauncher` sets; Lighting
   and FMOD don't matter on a server. The env contract is in `tools/macos-native-libs-brief.md`,
   change it only through Claude. The same launcher also sets `BOX64_DYNAREC_STRONGMEM=3` and
   `BOX64_DYNAREC_BIGBLOCK=0`, both slow but safe; keep them unless measured.
5. Parked, unchanged: a foreground service and wakelock so hosting survives the background.
