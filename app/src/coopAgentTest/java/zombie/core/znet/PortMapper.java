package zombie.core.znet;

public class PortMapper {
    public static boolean found, mapped, missingNative, failAddress;
    public static String address = "203.0.113.4";
    private static native void unavailable();
    public static boolean discover() { if (missingNative) unavailable(); return found; }
    public static boolean addMapping(int external, int internal, String label, String protocol, int lease, boolean force) {
        return mapped;
    }
    public static String getExternalAddress() {
        if (failAddress) throw new IllegalStateException("probe");
        return address;
    }
}
