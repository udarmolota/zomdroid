package zombie.network;

/** Fixture with the exact call site used by the game. Never packaged in the APK. */
public final class CoopMaster {
    public Process launchServer() throws java.io.IOException {
        return new ProcessBuilder("jre/bin/java", "zombie.network.GameServer", "-coop").start();
    }
}
