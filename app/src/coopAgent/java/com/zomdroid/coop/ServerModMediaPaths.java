package com.zomdroid.coop;

import java.io.File;
import java.security.ProtectionDomain;
import java.lang.instrument.ClassFileTransformer;
import com.zomdroid.shaded.net.bytebuddy.jar.asm.*;
import com.zomdroid.shaded.net.bytebuddy.utility.OpenedClassReader;

/**
 * Build 42 server fix: a mod with animation files (media/AnimSets, media/actiongroups) stopped
 * the server from starting.
 *
 * <p>Hosting runs the game with {@code -cachedir=coop-probe}, and {@code coop-probe/mods} is a
 * link to the instance's {@code Zomboid/mods} (HostingProfileMods). {@code AdvancedAnimator
 * .loadModMedia(dir)} builds its base URI from {@code dir.getCanonicalFile()} - the real
 * {@code Zomboid/mods/...} - but walks the folders under the raw {@code coop-probe/mods/...}
 * spelling. With no common prefix, {@code URI.relativize} returns the file's whole absolute path,
 * {@code buildChecksum} then finds no such key in the active file map and throws
 * {@code IllegalStateException: couldn't find "<absolute path>"}, which aborts
 * {@code GameServer.doMinimumInit}: no server Lua, no worldgen table, "Server Terminated".
 *
 * <p>The fix canonicalises the mod directory on entry, so the base URI and the walked paths agree
 * and the keys come out relative, exactly as on a client or a PC server. Nothing else changes.
 */
public final class ServerModMediaPaths implements ClassFileTransformer {
    private static final String TARGET = "zombie/core/skinnedmodel/advancedanimation/AdvancedAnimator";
    private static final String METHOD = "loadModMedia";
    private static final String DESC = "(Ljava/lang/String;)Ljava/util/List;";

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
            ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(name)) return null;
        return patch(bytes);
    }

    /** The patched class, or null when the method is not found exactly once. Public for tests. */
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
                    return new MethodVisitor(Opcodes.ASM9, target) {
                        @Override public void visitCode() {
                            super.visitCode();
                            // dir = ServerModMediaPaths.canonical(dir); - no branches, frames untouched.
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/zomdroid/coop/ServerModMediaPaths", "canonical",
                                    "(Ljava/lang/String;)Ljava/lang/String;", false);
                            super.visitVarInsn(Opcodes.ASTORE, 0);
                        }
                    };
                }
            }, 0);
            if (patched[0] != 1) {
                System.err.println("[coop] Unsupported AdvancedAnimator: expected one loadModMedia, found " + patched[0]);
                return null;
            }
            System.err.println("[coop] Build 42 mod animation path fix installed");
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.err.println("[coop] Cannot install mod animation path fix");
            failure.printStackTrace();
            return null;
        }
    }

    /** The real path of a mod directory; the input unchanged when it cannot be resolved. */
    public static String canonical(String dir) {
        if (dir == null) return null;
        try {
            return new File(dir).getCanonicalPath();
        } catch (Throwable e) {
            return dir;
        }
    }
}
