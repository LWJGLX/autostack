package autostack;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.TryCatchBlockSorter;

public class AutostackAgent implements Opcodes, ClassFileTransformer {
    private static final String MEMORYSTACK = "org/lwjgl/system/MemoryStack";
    private static final String STACK = "autostack/Stack";

    private static boolean atLeastJava7() {
        String propValue = System.getProperty("java.class.version");
        return propValue.startsWith("52.") || propValue.startsWith("51.");
    }

    private static String packageClassPrefix;

    public static void premain(String agentArguments, Instrumentation instrumentation) {
        packageClassPrefix = agentArguments == null ? "" : agentArguments.replace('.', '/');
        instrumentation.addTransformer(new AutostackAgent());
    }

    public byte[] transform(ClassLoader loader, final String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {
        if (className == null
                || className.startsWith("java/")
                || className.startsWith("sun/")
                || className.startsWith("org/lwjgl/vulkan/")
                || className.startsWith("org/lwjgl/system/")
                || !className.startsWith(packageClassPrefix))
            return null;
        ClassReader cr = new ClassReader(classfileBuffer);
        final Map<String, Integer> stackMethods = new HashMap<String, Integer>();
        // Scan all methods that need auto-stack
        cr.accept(new ClassVisitor(ASM5) {
            public MethodVisitor visitMethod(int access, final String methodName, final String methodDesc, String signature, String[] exceptions) {
                MethodVisitor mv = new MethodVisitor(ASM5) {
                    boolean mark;

                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == INVOKESTATIC && !itf && (
                                owner.startsWith("org/lwjgl/")
                                && (name.equals("mallocStack") ||
                                    name.equals("stackGet") ||
                                    name.equals("callocStack"))
                                ) || (owner.equals(STACK) &&
                                        (name.equals("stackMallocInt") ||
                                         name.equals("stackMallocLong") ||
                                         name.equals("stackMallocFloat") ||
                                         name.equals("stackMallocPointer")))
                            ) {
                            mark = true;
                        }
                    }

                    public void visitMaxs(int maxStack, int maxLocals) {
                        if (mark) {
                            stackMethods.put(methodName + methodDesc, maxLocals);
                        }
                    }
                };
                return mv;
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (stackMethods.isEmpty())
            return null;

        // Now, transform all such methods
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | (atLeastJava7() ? ClassWriter.COMPUTE_FRAMES : 0));
        cr.accept(new ClassVisitor(ASM5, cw) {
            public MethodVisitor visitMethod(final int access, String name, final String desc, String signature, String[] exceptions) {
                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                final Integer stackVar = stackMethods.get(name + desc);
                if (stackVar == null)
                    return mv;
                MethodVisitor tcbs = new TryCatchBlockSorter(mv, access, name, desc, signature, exceptions);
                MethodVisitor own = new MethodVisitor(ASM5, tcbs) {
                    Label tryLabel = new Label();
                    Label finallyLabel = new Label();

                    public void visitInsn(int opcode) {
                        if (opcode >= IRETURN && opcode <= RETURN) {
                            mv.visitVarInsn(ALOAD, stackVar.intValue());
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "pop", "()L" + MEMORYSTACK + ";", false);
                            mv.visitInsn(POP);
                        }
                        mv.visitInsn(opcode);
                    }

                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == INVOKESTATIC && owner.startsWith("org/lwjgl/") && (name.equals("mallocStack") || name.equals("callocStack"))) {
                            String newName = name.substring(0, 6);
                            mv.visitVarInsn(ALOAD, stackVar.intValue());
                            if (desc.startsWith("(I"))
                                mv.visitInsn(SWAP);
                            mv.visitMethodInsn(opcode, owner, newName, "(L" + MEMORYSTACK + ";" + desc.substring(1), false);
                        } else if (opcode == INVOKESTATIC && owner.equals(MEMORYSTACK) && name.equals("stackGet")) {
                            mv.visitVarInsn(ALOAD, stackVar.intValue());
                        } else if (opcode == INVOKESTATIC && owner.equals(STACK) &&
                                (name.equals("stackMallocInt") ||
                                 name.equals("stackMallocLong") ||
                                 name.equals("stackMallocFloat") ||
                                 name.equals("stackMallocPointer") ||
                                 name.equals("stackCallocInt") ||
                                 name.equals("stackCallocLong") ||
                                 name.equals("stackCallocFloat") ||
                                 name.equals("stackCallocPointer"))) {
                            String newName = name.substring(5, 6).toLowerCase() + name.substring(6);
                            mv.visitVarInsn(ALOAD, stackVar.intValue());
                            mv.visitInsn(SWAP);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, newName, desc, itf);
                        } else {
                            mv.visitMethodInsn(opcode, owner, name, desc, itf);
                        }
                    }

                    public void visitCode() {
                        mv.visitCode();
                        mv.visitMethodInsn(INVOKESTATIC, MEMORYSTACK, "stackPush", "()L"+ MEMORYSTACK + ";", false);
                        mv.visitVarInsn(ASTORE, stackVar.intValue());
                        mv.visitLabel(tryLabel);
                    }

                    public void visitEnd() {
                        mv.visitLabel(finallyLabel);
                        mv.visitVarInsn(ALOAD, stackVar.intValue());
                        mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "pop", "()L" + MEMORYSTACK + ";", false);
                        mv.visitInsn(POP);
                        mv.visitInsn(ATHROW);
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
