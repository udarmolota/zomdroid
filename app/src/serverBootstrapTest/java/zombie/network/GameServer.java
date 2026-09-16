package zombie.network;

/** Fixture runs in a child JVM so System.exit exercises the real bootstrap shutdown hook. */
public final class GameServer {
    public static int getPlayerCount() { return 2; }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("failure")) throw new IllegalStateException("fixture failure");
        if (args[0].equals("clean")) {
            Thread.sleep(5500);
            java.nio.file.Files.write(java.nio.file.Paths.get(System.getProperty("zomdroid.server.output")),
                    "Saving finish\nShutdown handling finished\n".getBytes("UTF-8"));
        }
        System.exit(0);
    }
}
