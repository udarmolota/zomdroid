package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Gives controller players back the A button on tile cursors (harvest, dig furrow, build, dig a
 * grave and every other "pick a tile" action), broken by the game itself in Build 42.22.
 *
 * 42.22's DoTileBuilding() (media/lua/server/BuildingObjects/ISBuildingObject.lua) stopped reading
 * the build button for controller players: it only sets isLeftDown = false for them, so
 * draggingItem.build is never set and A on the tile does nothing, with no error anywhere. The
 * button itself still maps to the controller's Interact in Java.
 *
 * The fix is our own Lua file dropped into media/lua/client (the game loads every file there),
 * the same code as the standalone ZomdroidControllerFix mod: it wraps the global DoTileBuilding
 * and sets the flag the way 42.21 did. The game's files are not edited. The file is written only
 * while the installed ISBuildingObject.lua carries the broken 42.22 branch and removed otherwise,
 * so a TIS fix retires it on its own. Runs at every launch, so existing instances are covered.
 */
public final class ControllerTileCursorFix {
    private static final String LOG_TAG = ControllerTileCursorFix.class.getSimpleName();

    private static final String BUILDING_OBJECT_REL = "media/lua/server/BuildingObjects/ISBuildingObject.lua";
    private static final String FIX_REL = "media/lua/client/ZomdroidControllerFix.lua";
    /** The 42.22 branch that skips the build button for controller players. */
    private static final String BROKEN_MARKER = "if JoypadState.players[draggingItem.player + 1] then";

    static final String FIX_LUA =
            "-- Written by the Zomdroid launcher while the game's DoTileBuilding() ignores the\n"
            + "-- controller's build button (Build 42.22): A on a tile cursor (harvest, dig, build...)\n"
            + "-- did nothing. Same code as the ZomdroidControllerFix mod; the shared flag keeps the two\n"
            + "-- from wrapping twice. Removed by the launcher once the game is fixed.\n"
            + "\n"
            + "local function wrap()\n"
            + "    if type(DoTileBuilding) ~= \"function\" or ZomdroidControllerFixInstalled then return end\n"
            + "    local original = DoTileBuilding\n"
            + "\n"
            + "    DoTileBuilding = function(draggingItem, isRender, x, y, z, square)\n"
            + "        local playerNum = draggingItem and draggingItem.player\n"
            + "        if playerNum and JoypadState.players[playerNum + 1] and not draggingItem.build then\n"
            + "            local playerObj = getSpecificPlayer(playerNum)\n"
            + "            if playerObj and playerObj:isBuildButtonReleased() then\n"
            + "                draggingItem.build = draggingItem.canBeBuild\n"
            + "            end\n"
            + "        end\n"
            + "        return original(draggingItem, isRender, x, y, z, square)\n"
            + "    end\n"
            + "\n"
            + "    ZomdroidControllerFixInstalled = true\n"
            + "end\n"
            + "\n"
            + "-- ISBuildingObject.lua is a server file and loads after client files.\n"
            + "Events.OnGameStart.Add(wrap)\n";

    private ControllerTileCursorFix() {}

    public static void applyIfNeeded(GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) return;
        File game = new File(gameInstance.getGamePath());
        File buildingObject = new File(game, BUILDING_OBJECT_REL);
        File fix = new File(game, FIX_REL);
        try {
            boolean broken = buildingObject.isFile()
                    && new String(Files.readAllBytes(buildingObject.toPath()), StandardCharsets.UTF_8)
                    .contains(BROKEN_MARKER);
            if (!broken) {
                if (fix.isFile() && fix.delete()) Log.i(LOG_TAG, "Game no longer needs the controller fix, removed it");
                return;
            }
            byte[] wanted = FIX_LUA.getBytes(StandardCharsets.UTF_8);
            if (fix.isFile() && java.util.Arrays.equals(Files.readAllBytes(fix.toPath()), wanted)) return;

            File tmp = new File(fix.getAbsolutePath() + ".tmp");
            Files.write(tmp.toPath(), wanted);
            Files.move(tmp.toPath(), fix.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Log.i(LOG_TAG, "Installed the controller tile-cursor fix");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to install the controller tile-cursor fix", e);
        }
    }
}
