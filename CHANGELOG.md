## [1.4.1] - 2026-04-04
### Added
✅ Voice Chat Support (Experimental)
Multiplayer voice chat is now enabled. Note: this feature is experimental — it may cause crashes on some devices when receiving incoming voice from other players. We need more data to investigate and fix these issues.
If you experience problems, we'd greatly appreciate detailed feedback including your device model, chipset, and game build version.

✅ Version Checker
Tap the new "Check for updates" item in the navigation menu to see your current version and check if a newer release is available on GitHub. A direct link to the release is provided if an update is found.

✅ Environment Variables
Added an Environment Variables field in Settings — useful for advanced rendering tweaks and GPU driver flags (e.g. TU_DEBUG, ZINK_DEBUG, LIBGL_*).

✅ Touchpad & Mouse Stick — Tap to Click
Single tap on the Touchpad and Mouse Stick on-screen controls now sends a left mouse click.

✅ Preset Info
When creating a new game instance, selecting a build preset (Build 41 / 42 / 42.12+) now shows a short description — recommended RAM, supported devices, and what to expect.

✅ Localization
More strings have been localized across the app into Russian, Portuguese (Brazil) and Chinese (Simplified).

## [1.4.0] - 2026-03-20
### Added
**✅ Custom Vulkan Driver**
You can now import your own Snapdragon Vulkan driver (.so file) via the new Import/Export Custom Driver menu item.
The driver is loaded alongside the built-in ones — select Custom Driver in Settings after importing.
Export your loaded driver to share or back it up — note that it will be lost on uninstall.

**✅ Game Log Export**
Added Export Game Log menu item — exports console.txt directly from the game folder.
Useful for bug reports and troubleshooting. If the game crashed before creating the Zomboid folder, the export will show a clear message.

**✅ Gamepad Fix (Split Screen)**
Fixed gamepad inputs being blocked when a physical keyboard is connected (bug in v1.3.7).
Split screen co-op with keyboard + gamepad now works correctly.

**✅ Localization**
Added Russian by AI, Portuguese (Brazil) by AI and Chinese (Simplified) by AI + @neighbor-bear translations.
More strings have been extracted and localized across the app.

**✅ UI & Other**
Added Reddit community link to the navigation menu.
App version is now displayed at the bottom of the navigation menu.

---

## [1.2.9.v4] - 2025-12-24
### Added
**🚀 On-Screen buttons: added a high-contrast outline.**

- So they don’t blend into very bright in-game scenes.

**🛠 Fixes** 

- **Multiplayer**: fixed crashes when trying to connect to a server (b41.78).

---

## [1.2.9] - 2025-12-5
### 🆕 What's New
**🚀 Newer Java version.**

- New launcher version is out with one single change — we've upgraded from Java 17 to the newer Java 21.

For better performance.

---


## [1.2.6] - 2025-11-02
### Added
**🚀 Detection of physical keyboard and mouse**
The launcher now detects connected keyboards and automatically enables PC-style layout in-game. For best stability, it's recommended to connect your keyboard and mouse before launching the game. Hotplug support is experimental and may behave inconsistently.

**🚀 Extended functionality for physical gamepads via customizable on-screen buttons**
If you're playing with a physical gamepad but need more control options, you can open the Controls Editor and create new MNK-type buttons mapped to existing keyboard keys. These MNK buttons will remain visible even when a gamepad is connected. For example, you can add Zoom+ / Zoom− buttons mapped to KEY_EQUAL / KEY_MINUS.

---
## [1.2.4.v2] - 2025-10-17
### Added
**🛠 Fixes**
- Fixed the issue with missing on-screen controls on MIUI devices.

**🚀 Native Library Integration**
- Added a new field for uploading native libraries from Project Zomboid developers (PZ build 42.12) when adding a new game instance.
- Multiplayer support (requers 2 native libs: libRakNet64.so, libZNetNoSteam64.so)

---

## [1.2.4] - 2025-10-13
### Added
**🛠 Fixes**
- Integrated some error fixes by @Wakort (v1.2.2), improving overall stability and compatibility.

**🎮 Gamepad Enhancements**
- Integrated extended gamepad support for broader device compatibility (from v1.2.3.v2).
- Integrated mapping configuration now persists after exiting the game (from v1.2.3.v2).

**🚀 Native Library Integration**
- Added native libraries from Project Zomboid developers (PZ build 42.12).
- Significant performance improvements, especially on build 42.
- Faster loading times and smoother gameplay experience.
- Known issue: crash occurs when opening the map on Build 42.8 and above  

---

## [1.2.3.v2] - 2025-09-29
### Added
- **Extended gamepad support for triggers (LT/RT).**  
  The launcher now correctly handles triggers regardless of how the device reports them:
  - as **axes** (`AXIS_LTRIGGER` / `AXIS_RTRIGGER`, or fallbacks like `Z`/`RZ`, `BRAKE`/`GAS`);
  - or as **buttons** (`KEYCODE_BUTTON_L2` / `KEYCODE_BUTTON_R2`).

- **LT/RT mapping in the setup wizard.**  
  The mapping wizard now includes dedicated steps for LT and RT, allowing proper configuration even when a device only sends button events.

- **Persistent custom mapping.**  
  User-defined layouts are saved in `SharedPreferences` and automatically loaded at startup, so mappings no longer reset after exiting the game.

### How it works
- We **normalize trigger input** to the standard GLFW layout:  
  - **Analog triggers** are mapped to axes `a4` (LT) and `a5` (RT).  
  - If a device only reports **buttons (L2/R2)**, we **synthesize axis values**: press → `1.0`, release → `0.0`.  
- This ensures the game always sees the expected axes, regardless of controller quirks.

### Changed
- The mapping wizard now runs through 12 steps (added LT/RT).  
- Saved mapping format expanded with dedicated slots for LT/RT, but remains backward-compatible.

### Notes
- D-Pad logic remains unchanged (future improvements planned separately).  

### Troubleshooting
- If triggers feel unresponsive or behave incorrectly (e.g. bound to the right stick):
  1. Restart the mapping wizard and reassign LT/RT.  
  2. Confirm that custom mappings are auto-loaded at launcher startup.  
  3. If issues persist, please provide your gamepad model and raw axis log output.

### Thanks
- Huge thanks to testers for logs and reports — they made it possible to build a flexible trigger conversion layer.

---

## [1.2.3] – 2025-09-15
### Added
- Fixed GUIDE button stuck in the Mapping section - the GUIDE/HOME button removed from the Mapping process.

---

## [1.2.0] – 2025-09-14
### Added
- Initial release with basic gamepad support by @shimux0.  
- Default mapping for standard Android-compatible controllers.  
