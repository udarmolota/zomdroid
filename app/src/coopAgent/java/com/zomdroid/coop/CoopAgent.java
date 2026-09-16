package com.zomdroid.coop;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.utility.OpenedClassReader;

/** Reuses shaded ASM from the already-bundled Zomdroid agent. No game files are modified. */
public final class CoopAgent {
    public static void premain(String options, Instrumentation instrumentation) {
        if ("server-upnp".equals(options)) {
            if (Boolean.getBoolean("zomdroid.server.upnpGuard"))
                instrumentation.addTransformer(new ServerUpnpGuard());
            instrumentation.addTransformer(new ServerInternetObserver());
            return;
        }
        System.out.println("[coop] Installing server process bridge");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (!"zombie/network/CoopMaster".equals(name)) return null;
                try {
                // Build 42.20 uses Java 25 class files. Byte Buddy's forward-compatible
                // reader preserves the original version while accepting this bytecode.
                ClassReader reader = OpenedClassReader.of(bytes, true);
                ClassWriter writer = new ClassWriter(reader, 0);
                final int[] replaced = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc,
                            String signature, String[] exceptions) {
                        MethodVisitor delegate = super.visitMethod(access, method, desc, signature, exceptions);
                        if (!method.equals("launchServer")) return delegate;
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
                                if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("java/lang/ProcessBuilder")
                                        && name.equals("start") && descriptor.equals("()Ljava/lang/Process;")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/zomdroid/coop/FileProcess",
                                            "start", "(Ljava/lang/ProcessBuilder;)Ljava/lang/Process;", false);
                                    replaced[0]++;
                                } else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            }
                        };
                    }
                }, 0);
                if (replaced[0] != 1) {
                    System.err.println("[coop] Unsupported CoopMaster: expected 1 start call, found " + replaced[0]);
                    return null;
                }
                System.out.println("[coop] CoopMaster process creation routed to Android");
                return writer.toByteArray();
                } catch (Throwable failure) {
                    System.err.println("[coop] Failed to transform CoopMaster");
                    failure.printStackTrace();
                    return null;
                }
            }
        });
    }
}
