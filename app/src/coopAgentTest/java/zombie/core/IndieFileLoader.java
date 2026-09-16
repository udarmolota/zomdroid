package zombie.core;

import java.io.FileNotFoundException;
import java.io.InputStreamReader;

/** Fixture whose body must remain active for every path outside cachedir/Server. */
public final class IndieFileLoader {
    public static InputStreamReader getStreamReader(String path, boolean ignore) throws FileNotFoundException {
        throw new FileNotFoundException("fixture-original:" + path);
    }
}
