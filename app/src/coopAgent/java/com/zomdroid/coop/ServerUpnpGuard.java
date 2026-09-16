package com.zomdroid.coop;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.utility.OpenedClassReader;

/** Server-only Build 41 guard: preserve working UPnP, tolerate missing JNI entry points. */
public final class ServerUpnpGuard implements ClassFileTransformer {
    @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
            ProtectionDomain domain, byte[] bytes) {
        if (!"zombie/core/znet/PortMapper".equals(name)) return null;
        try {
            ClassReader reader = OpenedClassReader.of(bytes, true);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public MethodVisitor visitMethod(int access, String method, String desc,
                        String signature, String[] exceptions) {
                    MethodVisitor target = super.visitMethod(access, method, desc, signature, exceptions);
                    if ((access & Opcodes.ACC_PUBLIC) == 0 || (access & Opcodes.ACC_STATIC) == 0
                            || (access & Opcodes.ACC_NATIVE) != 0) return target;
                    return new MethodVisitor(Opcodes.ASM9, target) {
                        final Label start = new Label(), end = new Label(), handler = new Label();
                        @Override public void visitCode() {
                            super.visitCode();
                            super.visitLabel(start);
                        }
                        @Override public void visitMaxs(int stack, int locals) {
                            super.visitLabel(end);
                            super.visitTryCatchBlock(start, end, handler, "java/lang/UnsatisfiedLinkError");
                            super.visitLabel(handler);
                            super.visitInsn(Opcodes.POP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/zomdroid/coop/ServerInternetStatus",
                                    "unavailable", "()V", false);
                            super.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
                            super.visitLdcInsn("[coop] UPnP JNI unavailable: " + method + "; continuing without router mapping");
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                            Type result = Type.getReturnType(desc);
                            if (result.getSort() == Type.VOID) super.visitInsn(Opcodes.RETURN);
                            else if (result.getSort() == Type.OBJECT || result.getSort() == Type.ARRAY) {
                                super.visitInsn(Opcodes.ACONST_NULL);
                                super.visitInsn(Opcodes.ARETURN);
                            } else {
                                super.visitInsn(Opcodes.ICONST_0);
                                super.visitInsn(Opcodes.IRETURN);
                            }
                            super.visitMaxs(stack, locals);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            System.err.println("[coop] Build 41 server UPnP guard installed");
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.err.println("[coop] Cannot install UPnP guard");
            failure.printStackTrace();
            return null;
        }
    }
}
