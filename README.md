# Zomdroid

**Zomdroid** is a launcher for [Project Zomboid](https://projectzomboid.com) on Android devices.

>  This application is **not developed by The Indie Stone** and is **not affiliated with them** in any way.

## Features

- Supports **Project Zomboid Build 41** and **Build 42**
- Theoretically supports **Lua mods** (not fully tested)
- Currently **single-player only**

## Technical Limitations

Zomdroid uses the **Mesa Zink driver**, which translates OpenGL calls to Vulkan. For Zink to function correctly, a **compatible Vulkan driver** is required.

One such driver is **Freedreno Turnip**, which is **bundled with the application**. However, it will **only work on modern devices with Adreno GPU**.

An option exists in the launcher to use the **default system driver**, but:
  - **Zink may fail to initialize** and **application will crash**, or
  - You may experience **visual glitches**

## Roadmap

Planned features in order of priority:

1. Add support for additional rendering backends  
   → This will expand GPU and device compatibility
2. Optimize and improve performance
3. Add multiplayer support

## Prebuilt libraries and jars

Prebuilt binaries and JARs are located in the `zomdroid/app/src/main/assets/dependencies` folder,  
**except** for **Box64** and **GLFW**, which are built alongside the zomdroid APK.

All Zomdroid dependencies—**except Box64 and GLFW**—can be either:
- Built from the [zomdroid-dependencies](https://github.com/liamelui/zomdroid-dependencies) repository  
  (Mesa, LWJGL, Assimp, and JNIWrapper), or
- Downloaded from official sources (FMOD, standard GNU/Linux libraries)
  
## Credits & Third-Party Sources
- [OpenJDK](https://github.com/openjdk/jdk) (Android port by [PojavLauncherTeam](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch)) - Used as the Java backend

- [Box64](https://github.com/ptitSeb/box64) - Used as the emulation backend

- [Mesa](https://gitlab.freedesktop.org/mesa/mesa) - Used as the rendering backend (Zink, Freedreno Turnip driver)

- [GLFW](https://github.com/glfw/glfw) - Library for OpenGL, OpenGL ES and Vulkan development on the desktop (required by Project Zomboid)

- [LWJGL](https://github.com/LWJGL/lwjgl3) - Java game library (required by Project Zomboid)

- [Assimp](https://github.com/assimp/assimp) - Asset loading library (required by Project Zomboid)

- [FMOD](https://www.fmod.com/) (proprietary) - Audio library (required by Project Zomboid)

- [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) - Library for accessing and creating SQLite database files in Java (required by Project Zomboid)

- [Winlator](https://github.com/brunodev85/winlator), [Termux](https://github.com/termux/termux-app), and [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) - Served as inspiration and guidance

## Feedback

Please report issues or suggest features via [GitHub Issues](https://github.com/liamelui/zomdroid/issues)


