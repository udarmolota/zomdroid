package com.zomdroid.coop;

import java.security.ProtectionDomain;
import java.lang.instrument.ClassFileTransformer;
import com.zomdroid.shaded.net.bytebuddy.jar.asm.*;
import com.zomdroid.shaded.net.bytebuddy.utility.OpenedClassReader;

/**
 * Build 42 hosting fix: a mod whose files have capital letters lost its items, and picking mods in
 * the server settings did not save them.
 *
 * <p>Hosting runs the game with {@code -cachedir=coop-probe}, and {@code coop-probe/mods} is a
 * link to the instance's {@code Zomboid/mods} (HostingProfileMods). The game lists the mod folders
 * under the link, but several loaders build a mod's base URI from the folder's canonical, real
 * path and then walk the linked spelling. {@code ScriptManager.Load} is one: with no common prefix
 * {@code ZomboidFileSystem.getRelativeFile} hands back the file's whole path, lowercased, the
 * active file map has no such key, and the game opens that lowercase path on disk. An all-lowercase
 * mod survives by accident; "Vanilla_Firearm/Vanilla_Firearm_FireMuzzle.txt" is a
 * FileNotFoundException that aborts the whole script load - no items for any mod, and inside
 * {@code Core.ResetLua} it also stops the server settings from recording the chosen mods
 * (SuVanillaWeaponOverride, Workshop 3579960696, 2026-11-27).
 *
 * <p>The fix is at the source: {@code ZomboidFileSystem.getAllModFoldersAux(dir, out)} gets the
 * real path of the folder it is about to list, so every mod folder the game knows is spelled the
 * way it is in normal play and the base and the walked paths agree everywhere.
 * {@link ServerModMediaPaths} stays: it costs nothing and covers the same mismatch for animations.
 */
public final class ModFoldersRealPath implements ClassFileTransformer {
    private static final String TARGET = "zombie/ZomboidFileSystem";
    private static final String METHOD = "getAllModFoldersAux";
    private static final String DESC = "(Ljava/lang/String;Ljava/util/List;)V";

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
                    if (!METHOD.equals(method) || !DESC.equals(desc) || (access & Opcodes.ACC_STATIC) != 0)
                        return target;
                    patched[0]++;
                    return new MethodVisitor(Opcodes.ASM9, target) {
                        @Override public void visitCode() {
                            super.visitCode();
                            // dir = ServerModMediaPaths.canonical(dir); - no branches, frames untouched.
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/zomdroid/coop/ServerModMediaPaths", "canonical",
                                    "(Ljava/lang/String;)Ljava/lang/String;", false);
                            super.visitVarInsn(Opcodes.ASTORE, 1);
                        }
                    };
                }
            }, 0);
            if (patched[0] != 1) {
                // Build 41 has no such method: nothing to fix there, and nothing is changed.
                return null;
            }
            System.err.println("[coop] Build 42 mod folders real path fix installed");
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.err.println("[coop] Cannot install mod folders real path fix");
            failure.printStackTrace();
            return null;
        }
    }
}
