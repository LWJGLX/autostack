package autostack;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.TryCatchBlockSorter;

public class AutostackAgent implements Opcodes, ClassFileTransformer {
    private static boolean atLeastJava7() {
        String propValue = System.getProperty("java.class.version");
        return propValue.startsWith("52.") || propValue.startsWith("51.");
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new AutostackAgent());
    }

    public byte[] transform(ClassLoader loader, final String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {
        if (className == null || className.startsWith("java/")
                || className.startsWith("org/lwjgl/vulkan/")
                || className.startsWith("org/lwjgl/system/"))
            return null;
        ClassReader cr = new ClassReader(classfileBuffer);
        final Set<String> stackMethods = new HashSet<String>();
        // Scan all methods that need auto-stack
        cr.accept(new ClassVisitor(ASM5) {
            public MethodVisitor visitMethod(int access, final String methodName, final String methodDesc, String signature, String[] exceptions) {
                MethodVisitor mv = new MethodVisitor(ASM5) {
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == INVOKESTATIC && owner.startsWith("org/lwjgl/") && (
                                name.equals("mallocStack") ||
                                name.equals("stackGet") ||
                                name.equals("callocStack")) && !itf) {
                            stackMethods.add(methodName + methodDesc);
                        }
                    }
                };
                return mv;
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (stackMethods.isEmpty())
            return null;

        // Now, transform all such methods
        ClassWriter cw = new ClassWriter(cr, 0 | (atLeastJava7() ? ClassWriter.COMPUTE_FRAMES : 0));
        cr.accept(new ClassVisitor(ASM5, cw) {
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!stackMethods.contains(name + desc))
                    return mv;
                MethodVisitor tcbs = new TryCatchBlockSorter(mv, access, name, desc, signature, exceptions);
                MethodVisitor own = new MethodVisitor(ASM5, tcbs) {
                    Label tryLabel = new Label();
                    Label finallyLabel = new Label();

                    public void visitCode() {
                        mv.visitCode();
                        mv.visitMethodInsn(INVOKESTATIC, "org/lwjgl/system/MemoryStack", "stackPush", "()Lorg/lwjgl/system/MemoryStack;", false);
                        mv.visitLabel(tryLabel);
                    }

                    public void visitEnd() {
                        mv.visitLabel(finallyLabel);
                        mv.visitMethodInsn(INVOKESTATIC, "org/lwjgl/system/MemoryStack", "stackPop", "()Lorg/lwjgl/system/MemoryStack;", false);
                        mv.visitInsn(RETURN);
                        mv.visitTryCatchBlock(tryLabel, finallyLabel, finallyLabel, "java/lang/Exception");
                        mv.visitEnd();
                    }
                };
                return own;
            }
        }, 0);
        byte[] arr = cw.toByteArray();
        return arr;
    }
}
