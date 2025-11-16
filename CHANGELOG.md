## [1.2.8] - 2025-11-16
### Added
**🚀 On-Screen Layout to File**

- About On-Screen Controls Saving

All layout changes are saved automatically to a file:
`instances → Project Zomboid → game → controls → controls.json`

Before uninstalling the app:

1. Back up the controls folder with the file to your device
2. After installing the new version and creating a new game instance, copy the folder back to the same path
3. Only then open the button editor (if needed)

**🛠 Fixes** 

- Fixed bug where some devices incorrectly detected the right stick as the left one. Improved detection of the D-pad on Switch 2 Pro gamepads.

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
