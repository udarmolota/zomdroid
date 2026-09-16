package com.zomdroid.coop;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.utility.OpenedClassReader;

/** Observes the game's own UPnP calls; never discovers/maps ports independently. */
public final class ServerInternetObserver implements ClassFileTransformer {
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
            ProtectionDomain domain, byte[] bytes) {
        if (!"zombie/core/znet/PortMapper".equals(name)) return null;
        try {
            ClassReader reader = OpenedClassReader.of(bytes, true);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc,
                        String signature, String[] exceptions) {
                    MethodVisitor target = super.visitMethod(access, method, desc, signature, exceptions);
                    if ((access & Opcodes.ACC_STATIC) == 0 || (access & Opcodes.ACC_NATIVE) != 0) return target;
                    boolean discover = method.equals("discover") && desc.equals("()Z");
                    boolean mapping = method.equals("addMapping") &&
                            (desc.equals("(IILjava/lang/String;Ljava/lang/String;IZ)Z") ||
                             desc.equals("(IILjava/lang/String;Ljava/lang/String;I)Z"));
                    if (!discover && !mapping) return target;
                    return new MethodVisitor(Opcodes.ASM9, target) {
                        @Override public void visitInsn(int opcode) {
                            if (opcode == Opcodes.IRETURN) {
                                super.visitInsn(Opcodes.DUP);
                                if (mapping) {
                                    super.visitVarInsn(Opcodes.ILOAD, 0);
                                    super.visitVarInsn(Opcodes.ALOAD, 3);
                                }
                                super.visitLdcInsn(Type.getObjectType(name));
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/zomdroid/coop/ServerInternetStatus", mapping ? "mapping" : "discovered",
                                        mapping ? "(ZILjava/lang/String;Ljava/lang/Class;)V" : "(ZLjava/lang/Class;)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.err.println("[coop] UPnP observation unavailable: " + failure);
            return null;
        }
    }
}
