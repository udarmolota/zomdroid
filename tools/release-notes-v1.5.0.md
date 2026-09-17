📢 Version v1.5.0 - Server hosting, dedicated server, native macOS libraries, GOG

**🛑A clean install is always the safer choice. It avoids leftovers from old installs and possible conflicts or even crashes. Don't forget to backup before.🛑**

## 🆕 What's new
### Main features
✅ **Server hosting on the phone** — turn on **Allow server hosting** in the instance settings, launch the game and choose **Host**. Friends on the same Wi-Fi can join right away. For players outside your network the game opens its port through the router's **UPnP**, and a status window shows whether the port was mapped and which addresses to share. Build 41 needs two multiplayer libraries, which you can download from Steam or load from a file in **Additional libraries**. While hosting is on, the game runs on its own profile with its own saves, mods and settings: turn the switch off again for normal play. The instance card shows a reminder while it is on. The limits have not been tested yet: how many players a phone server can take and how long a game on it can last. We will be glad to get your reports and feedback. Experimental.

<details>
<summary>Server memory</summary>

- **Build 42:** the server needs at least **2 GB** for two players.
- **Build 41:** **1 GB** is enough for two players.
- Both assume a stable, fast home Wi-Fi.

</details>

✅ **Dedicated server** — instance menu (⋮) → **Start Server** runs only the Project Zomboid server on the phone, without a local player, and others join from their devices. Handy if you have two Android devices at home: one runs just the server, so it can get more memory and take more players. Continue a world by its profile name or create a new one; set Java memory, port, maximum players, save interval, passwords, mods and maps, or edit the PZ configuration files directly. The panel can be closed and the screen turned off while it runs, and **Save and stop** shuts it down cleanly. Experimental.
✅ **Native macOS libraries (Build 42.20+)** — three of the game's libraries (`libLighting`, `libPZPathFind`, `libPZPopMan`: lighting, pathfinding and zombie population) can run as native ARM64 code taken from the macOS version of the game instead of going through box64 emulation. Less CPU load and fewer freezes; testers on mid-range phones report higher FPS. Download them from Steam (you need to own the game; the full macOS game is not downloaded) or load a ZIP in **Additional libraries**. Installed libraries are used right away; single ones can be turned off under **Libraries in use**. Experimental.
✅ **Download from GOG** — sign in with your GOG account in the side menu, pick the game and download its Linux installer. **Create an instance** installs straight from it. The new-instance screen also accepts a ZIP with the GOG `.sh` installer inside.
✅ **Import/Export of game files** — the **Import/Export Game Settings** screen now has three tabs: game settings (`options.ini`), **sandbox presets** and **character builds and outfits**. Everything travels as a .zip. Builds and outfits are merged by name with the ones already on the phone; a preset with the same name is replaced.
✅ **Readable controls editor** — control types are shown with clear, translated names instead of internal IDs like `STICK_WASD` or `BUTTON_RECT`.
✅ **First steps on the home screen** — with no instance yet, the home screen explains what an instance is and offers **Quick start**, **Download from Steam** and **Download from GOG**.

### Changed
✅ **The game's own mouse cursor follows the game option live.** The Android pointer and the on-screen mouse arrow now hide or come back as soon as the game saves **Lock cursor to window** — no restart needed, and it also works on the hosting profile.
✅ **Sound and physics without emulation.** The game's own Android ARM64 FMOD library is used instead of the emulated x86_64 one (switch in **Additional libraries**), and on Build 42.20+ the game's own ARM64 Bullet physics library runs natively.
✅ **Updated wiki** — a new GOG section and updated first steps.

### Known issues
⚠️ Build 41 with KI5/Autotsar vehicles on GL4ES: the interface may turn green or disappear completely near those vehicles. Turning off reflections in the game settings avoids it.
⚠️ Phones with little free memory can still stutter while driving through towns.

##
⚠️ **Reminder
Zomdroid is only a launcher.** To run the game, you need the Linux version of the game files — for example, the game bought on Steam or GOG.

##
☕︎ You can support my work on [Buymeacoffee](https://buymeacoffee.com/udarmolota).

##
Sᴜʙsᴄʀɪʙᴇ If you want to stay up to date with changes, tips, and useful tweaks, feel free to join our [Zomdroid subreddit](https://www.reddit.com/r/zomdroid/).
