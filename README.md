> [!WARNING] 
> Development is currently on hold while I wait for an official response from The Indie Stone regarding potential sponsorship. Just to clarify — TIS is not blocking or interfering with development in any way. I'm simply waiting to hear back, as I don’t have the resources to continue without financial support.

# Zomdroid

**Zomdroid** is a launcher for [Project Zomboid](https://projectzomboid.com) on mobile Android devices.

> [!NOTE]
> This application is **not developed by The Indie Stone** and is **not affiliated with them** in any way.

## Features

- Supports **Project Zomboid Build 41** (tested on 41.78.16) and **Build 42** (up to 42.7, versions 42.8 and later are not supported) 
- Supports **Lua mods**
- Currently **single-player only**

## System requirements 

- Android 11
- 3GB of free RAM (usually achievable on devices with at least 5-6GB of total RAM)
- 10-15 GB of free internal storage for downloading and installing the game

## Roadmap

Planned features in order of priority:

1. Expand GPU and device compatibility
2. Optimize and improve performance
3. Add external input support (mouse and keyboard, physical controller) 
4. Add multiplayer support

## Prebuilt binaries and JARs

Prebuilt binaries and JARs are located in the `app/src/main/assets/dependencies` folder,  
**except** for **Box64** and **GLFW**, which are built alongside the Zomdroid APK.

All Zomdroid dependencies—**except Box64 and GLFW**—can be either:
- Built from the [zomdroid-dependencies](https://github.com/liamelui/zomdroid-dependencies) repository  
  (Mesa, LWJGL, Assimp, JNIWrapper, GL4ES, zomdroid-agent), or
- Downloaded from official sources (FMOD, standard GNU/Linux libraries, JRE from PojavLauncherTeam, SQLite JDBC)
  
## Supporting Development

This is an independent project. To help keep it going, financial contributions are welcome via [Ko-Fi](https://ko-fi.com/liamelui).

## Feedback

Please report issues or suggest features via [GitHub Issues](https://github.com/liamelui/zomdroid/issues)

## Credits & Third-Party Sources
- [OpenJDK](https://github.com/openjdk/jdk) (Android port by [PojavLauncherTeam](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch)) - Used as the Java backend

- [Box64](https://github.com/ptitSeb/box64) - Used as the emulation backend

- [GL4ES](https://github.com/ptitSeb/gl4es) - Used as the rendering backend

- [Mesa](https://gitlab.freedesktop.org/mesa/mesa) - Used as the rendering backend (Zink, Freedreno Turnip driver)

- [ByteBuddy](https://github.com/raphw/byte-buddy) - Used for java agent creation and runtime code generation

- [ANTLR](https://github.com/antlr/antlr4) - Used for shader parsing and lexing

- [GLFW](https://github.com/glfw/glfw) - Library for OpenGL, OpenGL ES and Vulkan development on the desktop (required by Project Zomboid)

- [LWJGL](https://github.com/LWJGL/lwjgl3) - Java game library (required by Project Zomboid)

- [Assimp](https://github.com/assimp/assimp) - Asset loading library (required by Project Zomboid)

- [FMOD](https://www.fmod.com/) (proprietary) - Audio library (required by Project Zomboid)

- [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) - Library for accessing and creating SQLite database files in Java (required by Project Zomboid)

- [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass) - Library that provides access to the hidden linker namespace functionality on Android 9+

- [Winlator](https://github.com/brunodev85/winlator), [Termux](https://github.com/termux/termux-app), and [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) - Served as inspiration and guidance

