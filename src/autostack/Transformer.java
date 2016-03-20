/*
 * (C) Copyright 2016 Kai Burjack

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.

 */
package autostack;

import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.TryCatchBlockSorter;
import org.objectweb.asm.util.TraceClassVisitor;

public class Transformer implements Opcodes, ClassFileTransformer {
    private static final boolean TRACE = getBooleanProperty("autostack.TRACE", false);
    private static final String MEMORYSTACK = "org/lwjgl/system/MemoryStack";
    private static final String STACK = "autostack/Stack";
    
    private String packageClassPrefix;
    private boolean debugTransform;
    private boolean debugRuntime;

    private static boolean getBooleanProperty(String prop, boolean def) {
        String value = System.getProperty(prop);
        if (value != null)
            return value.equals("") || Boolean.valueOf(value);
        return def;
    }

    public Transformer(String packageClassPrefix) {
        this(packageClassPrefix, false, false);
    }

    public Transformer(String packageClassPrefix, boolean debugTransform, boolean debugRuntime) {
        this.packageClassPrefix = packageClassPrefix != null ? packageClassPrefix.replace('.', '/') : "";
        this.debugTransform = debugTransform;
        this.debugRuntime = debugRuntime;
    }

    public byte[] transform(ClassLoader loader, final String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null
                || className.startsWith("java/")
                || className.startsWith("sun/")
                || className.startsWith("org/lwjgl/")
                || !className.startsWith(packageClassPrefix))
            return null;
        ClassReader cr = new ClassReader(classfileBuffer);
        final class ForLoop {
            int loopConditionLabel;
            int loopIncrementLabel; // <-- The label where a for-loop does its increment
            int loopEndLabel; // <-- The label of the end of the loop
            int loopBodyLabel; // <-- The loop body
        }
        final class Info {
            List<ForLoop> forLoops = new ArrayList<ForLoop>();
            boolean catches;
            int firstLine = -1;
        }
        final Map<String, Info> stackMethods = new HashMap<String, Info>();
        // Scan all methods that need auto-stack
        if (debugTransform)
            System.out.println("[autostack] Scan methods in class: " + className.replace('/', '.'));
        cr.accept(new ClassVisitor(ASM5) {
            public MethodVisitor visitMethod(final int access, final String methodName, final String methodDesc, String signature, String[] exceptions) {
                MethodVisitor mv = new MethodVisitor(ASM5) {
                    boolean mark, catches;
                    Map<Label, Integer> visitedLabels = new HashMap<Label, Integer>();
                    Map<Integer, Label> forwardJumps = new HashMap<Integer, Label>();
                    int insns = 0;
                    Info info = new Info();
                    ForLoop forLoop;

                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == INVOKESTATIC && !itf && (
                                owner.startsWith("org/lwjgl/") && (name.equals("mallocStack") ||name.equals("callocStack")) ||
                                owner.equals(MEMORYSTACK) && (name.equals("stackGet") || name.equals("stackPop") || name.equals("stackPush")) ||
                                owner.equals(STACK))) {
                            mark = true;
                        }
                    }

                    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        catches = true;
                    }

                    public void visitLineNumber(int line, Label start) {
                        if (info.firstLine == -1)
                            info.firstLine = line;
                    }

                    public void visitLabel(Label label) {
                        insns++;
                        visitedLabels.put(label, insns);
                    }

                    public void visitJumpInsn(int opcode, Label label) {
                        insns++;
                        if (visitedLabels.containsKey(label)) {
                            int loopBody = visitedLabels.get(label);
                            // The simplest condition of a loop -> a jump back to somewhere.
                            // That can be the beginning of the loop body without the condition
                            // or it can also be the condition which then jumps out of the loop
                            // to a further unvisited label. Everything is possible.
                            forLoop = new ForLoop();
                            // Remember the instruction index of the loop body label
                            forLoop.loopBodyLabel = loopBody;
                            // Check all forward jumps to see whether they jump out of the loop
                            for (Map.Entry<Integer, Label> jump : forwardJumps.entrySet()) {
                                // Check if the jump happens inside the loop
                                if (jump.getKey() > loopBody) {
                                    // Now check if the jump target is outside the loop
                                    Integer jumpTarget = visitedLabels.get(jump.getValue());
                                    if (jumpTarget == null) {
//                                        forLoop.breakInsns.add(jump.getKey());
                                    } else if (jumpTarget < insns) {
                                        // This is a condition label!
                                        // So this is where the loop handles its condition and breaks out
                                        // of itself when the condition is not met, or jumps back to the 
                                        // loop body if the condition is met.
                                        forLoop.loopConditionLabel = jumpTarget;
                                        // If we know, that we have a loop condition, then will generate the stack pop
                                        // right BEFORE that condition label.
                                    }
                                }
                            }
                            info.forLoops.add(forLoop);
                            forwardJumps.clear();
                        } else {
                            // It is a forward jump. This _could_ be a jump out of a loop
                            // which we haven't yet found. It can likewise be an unconditional jump to
                            // the condition of a loop. We don't know.
                            forwardJumps.put(insns, label);
                        }
                    }

                    public void visitEnd() {
                        if (mark) {
                            if (debugTransform)
                                System.out.println("[autostack]   Will transform method: " + className.replace('/', '.') + "." + methodName);
                            info.catches = catches;
                            stackMethods.put(methodName + methodDesc, info);
                        }
                        forwardJumps.clear();
                        visitedLabels.clear();
                    }
                };
                return mv;
            }
        }, ClassReader.SKIP_FRAMES);
        if (stackMethods.isEmpty())
            return null;

        // Now, transform all such methods
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(ASM5, cw) {
            public MethodVisitor visitMethod(final int access, final String name, final String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                final Info info = stackMethods.get(name + desc);
                if (info == null)
                    return mv;
                boolean catches = info.catches;
                if (debugTransform)
                    System.out.println("[autostack]   Transforming method: " + className.replace('/', '.') + "." + name);
                if (catches)
                    mv = new TryCatchBlockSorter(mv, access, name, desc, signature, exceptions);
                Type[] paramTypes = Type.getArgumentTypes(desc);
                boolean isStatic = (access & ACC_STATIC) != 0;
                final int additionalLocals = 1;
                final Object[] replacedLocals = new Object[paramTypes.length + additionalLocals + (isStatic ? 0 : 1)];
                replacedLocals[replacedLocals.length - 1] = MEMORYSTACK; // MemoryStack
                if (!isStatic)
                    replacedLocals[0] = className;
                int var = isStatic ? 0 : 1;
                for (int t = var; t < paramTypes.length; t++) {
                    Type type = paramTypes[t];
                    var += type.getSize();
                    switch (type.getSort()) {
                    case Type.INT:
                    case Type.BYTE:
                    case Type.SHORT:
                    case Type.CHAR:
                        replacedLocals[t] = INTEGER;
                        break;
                    case Type.LONG:
                        replacedLocals[t] = LONG;
                        break;
                    case Type.FLOAT:
                        replacedLocals[t] = FLOAT;
                        break;
                    case Type.DOUBLE:
                        replacedLocals[t] = DOUBLE;
                        break;
                    case Type.OBJECT:
                    case Type.ARRAY:
                        replacedLocals[t] = type.getInternalName();
                        break;
                    }
                }
                final int stackVarIndex = var;
                mv = new MethodVisitor(ASM5, mv) {
                    Label tryLabel = new Label();
                    Label finallyLabel = new Label();
                    int lastLine = info.firstLine;
                    int insns = 0;
                    boolean awaitingFrame1, awaitingFrame2;
                    int loopIndex = 0;

                    public void visitInsn(int opcode) {
                        if (opcode >= IRETURN && opcode <= RETURN) {
                            if (debugRuntime) {
                                mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitLdcInsn("[autostack] Pop stack because of return at " + className.replace('/', '.') + "." + name + ":" + lastLine);
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                            }
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "pop", "()L" + MEMORYSTACK + ";", false);
                            mv.visitInsn(POP);
                        }
                        mv.visitInsn(opcode);
                    }

                    public void visitLabel(Label label) {
                        insns++;
                        Info inf = info;
                        ForLoop forLoop = inf.forLoops.size() > loopIndex ? inf.forLoops.get(loopIndex) : null;
                        if (forLoop != null && forLoop.loopConditionLabel == insns) {
                            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                            mv.visitLdcInsn("[autostack] Loop end " + className.replace('/', '.') + "." + name);
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "pop", "()L" + MEMORYSTACK + ";", false);
                            mv.visitInsn(POP);
                        }
                        mv.visitLabel(label);
                        if (forLoop != null && forLoop.loopBodyLabel == insns) {
                            // This is the loop body. We need to push frame here
                            awaitingFrame2 = true;
                        }
                    }

                    public void visitJumpInsn(int opcode, Label label) {
                        insns++;
//                        if (info.loops.size() > loopIndex && info.loops.get(loopIndex).endJump == insns) {
//                            // This is a loop back-jump! Pop the stack here.
//                            if (debugTransform)
//                                System.out.println("[autostack]     emitting loop frame reset at line " + lastLine);
//                            mv.visitVarInsn(ALOAD, stackVarIndex);
//                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "pop", "()L" + MEMORYSTACK + ";", false);
//                            mv.visitInsn(POP);
//                        }
                        mv.visitJumpInsn(opcode, label);
                    }

                    public void visitVarInsn(int opcode, int var) {
                        if (var >= stackVarIndex)
                            var += additionalLocals;
                        mv.visitVarInsn(opcode, var);
                    }

                    public void visitIincInsn(int var, int increment) {
                        if (var >= stackVarIndex)
                            var += additionalLocals;
                        mv.visitIincInsn(var, increment);
                    }

                    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
                        if (type == F_FULL) {
                            // Augment the locals with our [MemoryStack]
                            Object[] locals = new Object[local.length + additionalLocals];
                            int replacementLength = replacedLocals.length;
                            System.arraycopy(replacedLocals, 0, locals, 0, replacementLength);
                            int len = locals.length - replacementLength;
                            System.arraycopy(local, replacementLength - additionalLocals, locals, replacementLength, len);
                            mv.visitFrame(type, nLocal + additionalLocals, locals, nStack, stack);
                        } else
                            mv.visitFrame(type, nLocal, local, nStack, stack);
                        if (awaitingFrame2) {
                            if (debugTransform)
                                System.out.println("[autostack]     emitting loop frame begin at line " + lastLine);
                            awaitingFrame2 = false;
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "push", "()L" + MEMORYSTACK + ";", false);
                            mv.visitInsn(POP);
                        }
                    }

                    public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
                        if (index >= stackVarIndex)
                            index++;
                        mv.visitLocalVariable(name, desc, signature, start, end, index);
                    }

                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == INVOKESTATIC && owner.startsWith("org/lwjgl/") && (name.equals("mallocStack") || name.equals("callocStack"))) {
                            String newName = name.substring(0, 6);
                            if (debugTransform)
                                System.out.println("[autostack]     rewrite invocation of " + owner.replace('/', '.') + "." + name + " at line " + lastLine + " --> aload " + stackVarIndex + "; invokestatic " + owner.replace('/', '.') + "." + newName);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            if (desc.startsWith("(I"))
                                mv.visitInsn(SWAP);
                            mv.visitMethodInsn(opcode, owner, newName, "(L" + MEMORYSTACK + ";" + desc.substring(1), false);
                        } else if (opcode == INVOKESTATIC && owner.equals(MEMORYSTACK) && name.equals("stackGet")) {
                            if (debugTransform)
                                System.out.println("[autostack]     rewrite invocation of " + owner.replace('/', '.') + "." + name + " at line " + lastLine + " --> aload " + stackVarIndex);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                        } else if (opcode == INVOKESTATIC && owner.equals(MEMORYSTACK) && (name.equals("stackPush") || name.equals("stackPop"))) {
                            String newName = "p" + name.substring(6);
                            if (debugTransform)
                                System.out.println("[autostack]     rewrite invocation of " + owner.replace('/', '.') + "." + name + " at line " + lastLine + " --> aload " + stackVarIndex + "; invokevirtual " + MEMORYSTACK.replace('/', '.') + "." + newName);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, newName, desc, itf);
                        } else if (opcode == INVOKESTATIC && owner.equals(STACK)) {
                            String newName = name.substring(0, 6) + name.substring(11);
                            if (debugTransform)
                                System.out.println("[autostack]     rewrite invocation of " + owner.replace('/', '.') + "." + name + " at line " + lastLine + " --> aload " + stackVarIndex + "; invokevirtual " + MEMORYSTACK.replace('/', '.') + "." + newName);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            mv.visitInsn(SWAP);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, newName, desc, itf);
                        } else {
                            mv.visitMethodInsn(opcode, owner, name, desc, itf);
                        }
                    }

                    public void visitLineNumber(int line, Label start) {
                        mv.visitLineNumber(line, start);
                        lastLine = line;
                    }

                    public void visitCode() {
                        mv.visitCode();
                        if (debugRuntime) {
                            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                            mv.visitLdcInsn("[autostack] Push stack at begin of " + className.replace('/', '.') + "." + name);
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                        }
                        mv.visitMethodInsn(INVOKESTATIC, MEMORYSTACK, "stackPush", "()L"+ MEMORYSTACK + ";", false);
                        mv.visitVarInsn(ASTORE, stackVarIndex);
                        mv.visitLabel(tryLabel);
                        mv.visitFrame(F_APPEND, 1, new Object[] { MEMORYSTACK }, 0, null);
                    }

                    public void visitMaxs(int maxStack, int maxLocals) {
                        mv.visitLabel(finallyLabel);
                        mv.visitFrame(F_FULL, replacedLocals.length, replacedLocals, 1, new Object[] {"java/lang/Throwable"});
                        mv.visitTryCatchBlock(tryLabel, finallyLabel, finallyLabel, null);
                        if (debugRuntime) {
                            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                            mv.visitLdcInsn("[autostack] Pop stack because of throw [");
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false);
                            mv.visitInsn(DUP);
                            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                            mv.visitInsn(SWAP);
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false);
                            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                            mv.visitLdcInsn("] at " + className.replace('/', '.') + "." + name + ":" + lastLine);
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                        }
                        mv.visitVarInsn(ALOAD, stackVarIndex);
                        mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "pop", "()L" + MEMORYSTACK + ";", false);
                        mv.visitInsn(POP);
                        mv.visitInsn(ATHROW);
                        mv.visitMaxs(maxStack + (debugRuntime ? 2 : 1), maxLocals + additionalLocals);
                    }
                };
                return mv;
            }
        }, 0);
        byte[] arr = cw.toByteArray();
        if (TRACE) {
            cr = new ClassReader(arr);
            cr.accept(new TraceClassVisitor(new PrintWriter(System.err)), 0);
        }
        return arr;
    }
}
