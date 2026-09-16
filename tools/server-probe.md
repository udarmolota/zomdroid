# Server startup probe

Experimental on-device hosting, added 2026-09-09. Build 42.20.0 was tested on Samsung
SM-S938B: the user entered a world through the in-game Host menu and walked around,
reporting roughly 60 FPS at a 60 FPS cap with occasional stutters. Second-player joining,
COOP save/exit/restart, long sessions and Build 41 remain unverified.

Per-instance Settings now offers **Allow server hosting (experimental)**, off by default.
Enable it, launch normally, then use Host in the game. No server starts just by enabling
the switch. The opt-in bridge and its JVM assets are packaged in debug and release.
It retains the tested `coop-probe` profile (including its existing worlds), a 2 GB client
heap, no Steam, no game debug mode and no client quick-save backup. Server heap comes
from the game's Host screen. Disabling the switch restores the ordinary launch path
without deleting the COOP profile. Background hosting is not guaranteed.

The earlier debug menu entries remain for diagnostics. The following describes their
standalone-server tests, not proof that all in-game COOP lifecycle checks passed.

First device run reached `*** SERVER STARTED ****` and LuaNet initialization completed.
`libPZClipper64.so` loaded from the game's ARM64 directory (this run therefore does not
prove the emulated Clipper route). VehiclesDB2 initialization, world loading, collision
data and population managers completed. The process was still alive after 1m37s total
runtime, with subsequent weather activity and roughly 1.6 GB RSS despite a 1 GB Java heap.
Save/quit subsequently completed (`Saving finish`, `Shutdown handling finished`, process
gone). A second run loaded the same seed and WorldDictionary.bin with `isNewGame=false`
and reached SERVER STARTED again; its save/quit also completed.

The local client then bound to `ServerProbeBindingService` and opened the normal
connection form. Server-to-client packet exchange is confirmed by an AccessDeniedPacket
(`An account password is required`) on the empty-password attempt. Authenticated entry
was subsequently confirmed; the user closed the client herself. A subsequent server
SIGABRT prompted automatic quit-on-unbind and exception-file diagnostics; their full
COOP lifecycle still needs a device test.

SQLite's first glibc candidate prints a load error on the client, but this is not a fatal
startup failure: `/proc/<client-pid>/maps` confirmed our bundled Android libsqlitejdbc.so
loaded, and `client-probe/db/ServerList.db` existed. Do not diagnose SQLite failure from
that initial exception alone.

Nonfatal output included a missing `server-probe/mods` directory, worldgen property
warnings and missing UI icons. These did not prevent reaching the server-started marker.

## Test

1. Install the debug APK and runtime dependencies. Prefer a clean Build 42.20+ instance.
2. Open the instance's overflow menu and choose **Server startup test (experimental)**.
3. Tap **Start server test**, keep the screen foreground, and inspect the output.
4. After SERVER STARTED, **Join from this phone** opens the client with the game's
   standard `+connect 127.0.0.1:16261` option. Use a regular test username/password in
   the connection form (the random administrator password is not needed).
5. Use **Request save and quit** and wait for shutdown output.
   Sending the command does not by itself confirm a save or successful shutdown.
6. **End test process** terminates only the probe process and permits another attempt.
   If startup is stuck, this can be used to abort; it does not save an active test world.
7. Export the instance's ordinary bug report from the launcher.

The Join button is the supported concurrent test path. It binds the visible client to
a debug-only service in the server process, maintaining its Android process importance.
The client uses its own `client-probe` cache, no Steam and a 2 GB Java heap. User renderer
selection is retained. Avoid other simultaneous launches or installations against these
same game files; both launch paths can apply native compatibility preparation.

## What is tested

The server process loads the same Android native launcher/JNI bridge used by the client.
It creates the ARM64 JVM in-process and uses the existing linker/box64 library bridge
for x86_64 game libraries. No external Java executable, lowered target SDK, client
Java agents, renderer context, or audio initialization is used.

Game classpath/native paths come from the selected instance. The probe prefers the
bundled JRE25, falling back to the legacy runtime directory, with 256 MB initial / 1 GB
maximum Java heap. User JVM/renderer overrides are omitted to keep the experiment repeatable.
The existing ZNetStatistics, native-library and pathfinder workarounds are applied.

`GameServer` receives a separate `server-probe` cache directory under the instance,
server name `zomdroid-probe`, no-Steam mode, a loopback bind, and a random admin password
to avoid the interactive first-start password prompt. This first test is not intended
for joining from another device. The actual build's acceptance of these options and
startup milestones must be checked in its log.

The console buttons feed `quit` to the process's stdin. The native main call returning
is not reported as success: it may mean startup failure or remaining background threads.

## Evidence to collect

- `server-native.log`: mirrored native stdout/stderr, box64 and JVM exceptions.
- `server-crash.txt`: the native signal handler's server-specific dump, if one exists.
- `server-console.txt`: the game's `server-probe/console.txt`, if created.
- `client-probe-console.txt`: the local client's separate game log, if created.
- `server/hs_err_pid*.log`: fatal JVM reports, if created.

These are added to the ordinary instance bug-report ZIP. Client native logs keep their
existing names. The probe does not start a second copy of the launcher's logcat rotator.

Look for whether `Clipper.init` passes, which native library fails next, and whether
SQLite, RakNet and world loading complete. A live process or a few seconds of output
does not establish that a server is ready. Subsequent work should follow that evidence.

## Recovered earlier work

Local archive: `C:/Users/user/Desktop/PZ/zomdroid-agent.7z`.
Source entry: `zomdroid-agent/src/main/java/com/zomdroid/agent/ZomdroidAgent.java`.
Log entry: `zomdroid-agent/local_game_log.txt`.

The recovered source intercepts ProcessBuilder, sets vfork, adjusts LD_LIBRARY_PATH and
copies libjava_exec.so. The included Aug 1 log ends with EACCES in CoopMaster.launchServer.
It is earlier than the Aug 6 box64 attempt recorded in TODO. No code from that agent
was incorporated into this probe.

Next after standalone startup: implement the CoopMaster child process contract (stdin,
stdout, exit status, cancellation), client/server memory budgets and Android lifecycle.
The current visible Activity does not provide background hosting.
