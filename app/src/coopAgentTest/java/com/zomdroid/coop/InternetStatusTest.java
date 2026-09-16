package com.zomdroid.coop;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import zombie.core.znet.PortMapper;

public final class InternetStatusTest {
    private static Path status;
    private static Properties read() throws IOException {
        Properties data = new Properties();
        try (InputStream in = Files.newInputStream(status)) { data.load(in); }
        return data;
    }
    private static void check(boolean success, String label) { if (!success) throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("coop-internet-test");
        status = directory.resolve("session.properties");
        Path report = directory.resolve("report.properties");
        System.setProperty("zomdroid.server.internetSession", status.toString());
        System.setProperty("zomdroid.server.internetFile", report.toString());
        PrintStream original = System.out;
        ByteArrayOutputStream protocol = new ByteArrayOutputStream();
        System.setOut(new PrintStream(protocol));
        try {
            check(!PortMapper.discover(), "discover false result");
            check("no_gateway".equals(read().getProperty("state")), "no gateway status");
            check("17261".equals(read().getProperty("port")), "custom server port");
            PortMapper.found = true;
            check(PortMapper.discover(), "discover true result");
            check("discovered".equals(read().getProperty("state")), "discovered status");
            check(!PortMapper.addMapping(17261, 17261, "test", "UDP", 86400, true), "map false result");
            check("mapping_failed".equals(read().getProperty("state")), "failed status");
            PortMapper.mapped = true;
            check(PortMapper.addMapping(17261, 17261, "test", "UDP", 86400, true), "map true result");
            Properties mapped = read();
            check("mapped".equals(mapped.getProperty("state")), "mapped status");
            check(PortMapper.address.equals(mapped.getProperty("externalAddress")), "external address");
            byte[] before = Files.readAllBytes(status);
            PortMapper.addMapping(17262, 17262, "secondary", "UDP", 86400, true);
            PortMapper.addMapping(17261, 17261, "tcp", "TCP", 86400, true);
            check(java.util.Arrays.equals(before, Files.readAllBytes(status)), "unrelated mappings ignored");
            PortMapper.failAddress = true;
            check(PortMapper.addMapping(17261, 17261, "test", "UDP", 86400, true), "address error preserves mapping");
            check(read().getProperty("externalAddress").isEmpty(), "unknown address");
            PortMapper.missingNative = true;
            check(!PortMapper.discover(), "B41 missing JNI guard");
            check("unavailable".equals(read().getProperty("state")), "guard return observed");
            check(java.util.Arrays.equals(Files.readAllBytes(status), Files.readAllBytes(report)), "report/session agree");
            check(!Files.exists(directory.resolve("session.properties.tmp")), "atomic publication finished");
            check(protocol.size() == 0, "UPnP observer polluted CoopSlave stdout");
        } finally {
            System.setOut(original);
            Files.deleteIfExists(status);
            Files.deleteIfExists(report);
            Files.deleteIfExists(directory);
        }
        System.out.println("PASS: UPnP results, custom port, address failure, B41 JNI guard, report and protocol isolation");
    }
}
