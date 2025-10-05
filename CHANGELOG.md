## [1.2.5] - 2025-10-5
### Added
- **Better build 42 Support**
- Added some performance optimizations  
- Implemented native library support for Build 42  
- Native libraries are loaded manually and provided separately  
- Known issue: crash occurs when opening the map on Build 42.8 and above  
- Changes authored by @Wakort
  
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
