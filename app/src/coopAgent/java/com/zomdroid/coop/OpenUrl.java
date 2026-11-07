package com.zomdroid.coop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.lang.instrument.ClassFileTransformer;
import com.zomdroid.shaded.net.bytebuddy.jar.asm.*;
import com.zomdroid.shaded.net.bytebuddy.utility.OpenedClassReader;

/**
 * Links in the game's menus (news, Discord, Steam pages) go through
 * {@code zombie.network.DesktopBrowser.openURL}, which runs "open", "rundll32" or a Linux browser
 * found with "which" - all refused on Android, so a click did nothing and logged
 * {@code Cannot run program "which"}. The method body is replaced: the URL is handed to the
 * launcher through a file ({@code -Dzomdroid.openUrlFile}), and GameActivity opens it in the
 * Android browser.
 */
public final class OpenUrl implements ClassFileTransformer {
    private static final String TARGET = "zombie/network/DesktopBrowser";
    private static final String METHOD = "openURL";
    private static final String DESC = "(Ljava/lang/String;)Z";

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
            ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(name)) return null;
        return patch(bytes);
    }

    /** The patched class, or null when openURL is not found exactly once. Public for tests. */
    public static byte[] patch(byte[] bytes) {
        try {
            ClassReader reader = OpenedClassReader.of(bytes, true);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            final int[] patched = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc,
                        String signature, String[] exceptions) {
                    MethodVisitor target = super.visitMethod(access, method, desc, signature, exceptions);
                    if (!METHOD.equals(method) || !DESC.equals(desc) || (access & Opcodes.ACC_STATIC) == 0)
                        return target;
                    patched[0]++;
                    // A fresh body; the original one is dropped (no visitor below for it).
                    target.visitCode();
                    target.visitVarInsn(Opcodes.ALOAD, 0);
                    target.visitMethodInsn(Opcodes.INVOKESTATIC, "com/zomdroid/coop/OpenUrl", "open",
                            "(Ljava/lang/String;)Z", false);
                    target.visitInsn(Opcodes.IRETURN);
                    target.visitMaxs(0, 0);
                    target.visitEnd();
                    return null;
                }
            }, 0);
            if (patched[0] != 1) {
                System.err.println("[coop] Unsupported DesktopBrowser: expected one openURL, found " + patched[0]);
                return null;
            }
            System.err.println("[coop] Game links open in the Android browser");
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.err.println("[coop] Cannot install the game link handler");
            failure.printStackTrace();
            return null;
        }
    }

    /** Hands the URL to the launcher. True when it was passed on, as the original reports. */
    public static boolean open(String url) {
        String path = System.getProperty("zomdroid.openUrlFile");
        if (url == null || path == null || path.isEmpty()) return false;
        try {
            File file = new File(path);
            File tmp = new File(path + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(url.getBytes(StandardCharsets.UTF_8));
            }
            // Rename so the launcher never reads half a URL.
            if (!tmp.renameTo(file)) {
                file.delete();
                if (!tmp.renameTo(file)) return false;
            }
            return true;
        } catch (Throwable e) {
            System.err.println("[coop] Cannot pass the link on: " + e);
            return false;
        }
    }
}
