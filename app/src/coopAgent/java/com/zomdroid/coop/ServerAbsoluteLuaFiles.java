package com.zomdroid.coop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.lang.instrument.ClassFileTransformer;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.utility.OpenedClassReader;

/**
 * Build 42 server fix. IndieFileLoader sends absolute cachedir/Server Lua paths through
 * ZomboidFileSystem's mod resolver, producing paths such as mods/data/data/.../Server.
 * Bypass that resolver only for canonical files immediately below this server's Server tree.
 */
public final class ServerAbsoluteLuaFiles implements ClassFileTransformer {
    private static final String TARGET = "zombie/core/IndieFileLoader";
    private static final String METHOD = "getStreamReader";
    private static final String DESC = "(Ljava/lang/String;Z)Ljava/io/InputStreamReader;";

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
            ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(name)) return null;
        try {
            ClassReader reader = OpenedClassReader.of(bytes, true);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            final int[] patched = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc,
                        String signature, String[] exceptions) {
                    MethodVisitor target = super.visitMethod(access, method, desc, signature, exceptions);
                    if (!METHOD.equals(method) || !DESC.equals(desc) || (access & Opcodes.ACC_STATIC) == 0)
                        return target;
                    patched[0]++;
                    return new MethodVisitor(Opcodes.ASM9, target) {
                        @Override public void visitCode() {
                            super.visitCode();
                            Label normal = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/zomdroid/coop/ServerAbsoluteLuaFiles", "open",
                                    "(Ljava/lang/String;)Ljava/io/InputStreamReader;", false);
                            super.visitInsn(Opcodes.DUP);
                            super.visitJumpInsn(Opcodes.IFNULL, normal);
                            super.visitInsn(Opcodes.ARETURN);
                            super.visitLabel(normal);
                            super.visitInsn(Opcodes.POP);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            if (patched[0] != 1) {
                System.err.println("[coop] Unsupported IndieFileLoader: expected one target, found " + patched[0]);
                return null;
            }
            System.err.println("[coop] Build 42 absolute server-Lua path guard installed");
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.err.println("[coop] Cannot install absolute server-Lua path guard");
            failure.printStackTrace();
            return null;
        }
    }

    /** Returns null unless the path is an approved absolute server configuration file. */
    public static InputStreamReader open(String path) throws IOException {
        if (path == null) return null;
        File requested = new File(path);
        if (!requested.isAbsolute()) return null;
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) return null;
        File server = new File(home, "Server").getCanonicalFile();
        File file = requested.getCanonicalFile();
        String prefix = server.getPath() + File.separator;
        if (!file.getPath().startsWith(prefix) || !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".lua"))
            return null;
        return new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
    }
}
