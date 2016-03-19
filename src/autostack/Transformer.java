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

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.TryCatchBlockSorter;

public class Transformer implements Opcodes, ClassFileTransformer {
    private static final String MEMORYSTACK = "org/lwjgl/system/MemoryStack";
    private static final String STACK = "autostack/Stack";
    
    private String packageClassPrefix;
    private boolean debugTransform;
    private boolean debugRuntime;

    public Transformer(String packageClassPrefix) {
        this(packageClassPrefix, false, false);
    }

    public Transformer(String packageClassPrefix, boolean debugTransform, boolean debugRuntime) {
        this.packageClassPrefix = packageClassPrefix != null ? packageClassPrefix.replace('.', '/') : "";
        this.debugTransform = debugTransform;
        this.debugRuntime = debugRuntime;
    }

    public byte[] transform(ClassLoader loader, final String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
        if (className == null
                || className.startsWith("java/")
                || className.startsWith("sun/")
                || className.startsWith("org/lwjgl/")
                || !className.startsWith(packageClassPrefix))
            return null;
        ClassReader cr = new ClassReader(classfileBuffer);
        final class Loop {
            int gotoJump;
            int breakJump;
            int endJump;
            int loopBody;
        }
        final class Info {
            List<Loop> loops = new ArrayList<Loop>();
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
                    int jumps = 0;
                    int labels = 0;
                    Info info = new Info();
                    Loop loop;

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
                        labels++;
                        visitedLabels.put(label, labels);
                    }

                    public void visitJumpInsn(int opcode, Label label) {
                        jumps++;
                        if (opcode == GOTO && !visitedLabels.containsKey(label) && loop == null) {
                            // GOTO to the condition
                            loop = new Loop();
                            loop.gotoJump = jumps;
                        } else if (opcode == GOTO && !visitedLabels.containsKey(label) && loop != null) {
                            // BREAK out of loop
                            loop.breakJump = jumps;
                        }
                        if (opcode != GOTO && visitedLabels.containsKey(label)) {
                            // conditional jump back to loop begin
                            loop.endJump = jumps;
                            loop.loopBody = visitedLabels.get(label);
                            info.loops.add(loop);
                        }
                    }

                    public void visitEnd() {
                        if (mark) {
                            if (debugTransform)
                                System.out.println("[autostack]   Will transform method: " + className.replace('/', '.') + "." + methodName);
                            info.catches = catches;
                            stackMethods.put(methodName + methodDesc, info);
                        }
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
                final Object[] replacedLocals = new Object[paramTypes.length + 1 + (isStatic ? 0 : 1)];
                replacedLocals[replacedLocals.length - 1] = MEMORYSTACK;
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
                    int jumps = 0;
                    int labels = 0;
                    boolean awaitingFrame;
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
                        labels++;
                        if (info.loops.size() > loopIndex && info.loops.get(loopIndex).loopBody == labels) {
                            mv.visitLabel(label);
                            awaitingFrame = true;
                        } else {
                            mv.visitLabel(label);
                        }
                    }

                    public void visitJumpInsn(int opcode, Label label) {
                        jumps++;
                        if (info.loops.size() > loopIndex && info.loops.get(loopIndex).gotoJump == jumps) {
                            // This is the initial GOTO to jump to the loop condition. Push here
                            if (debugTransform)
                                System.out.println("[autostack]     generating loop init push at line " + lastLine);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "push", "()L" + MEMORYSTACK + ";", false);
                            mv.visitInsn(POP);
                        } else if (info.loops.size() > loopIndex && info.loops.get(loopIndex).breakJump == jumps) {
                            // GOTO to break out of jump. Pull here
                            if (debugTransform)
                                System.out.println("[autostack]     generating loop break pop at line " + lastLine);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "pop", "()L" + MEMORYSTACK + ";", false);
                            mv.visitInsn(POP);
                        } else if (info.loops.size() > loopIndex && info.loops.get(loopIndex).endJump == jumps) {
                            // This is a loop back-jump! Generate stackPop
                            if (debugTransform)
                                System.out.println("[autostack]     generating loop next pop at line " + lastLine);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "pop", "()L" + MEMORYSTACK + ";", false);
                            mv.visitInsn(POP);
                            loopIndex++;
                        }
                        mv.visitJumpInsn(opcode, label);
                    }

                    public void visitVarInsn(int opcode, int var) {
                        if (var >= stackVarIndex)
                            var++;
                        mv.visitVarInsn(opcode, var);
                    }

                    public void visitIincInsn(int var, int increment) {
                        if (var >= stackVarIndex)
                            var++;
                        mv.visitIincInsn(var, increment);
                    }

                    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
                        if (type == F_FULL) {
                            Object[] locals = new Object[local.length + 1];
                            int replacementLength = replacedLocals.length;
                            System.arraycopy(replacedLocals, 0, locals, 0, replacementLength);
                            int len = local.length - replacementLength;
                            System.arraycopy(local, replacementLength - 1, locals, replacementLength, len);
                            mv.visitFrame(type, nLocal + 1, locals, nStack, stack);
                        } else
                            mv.visitFrame(type, nLocal, local, nStack, stack);
                        if (awaitingFrame) {
                            awaitingFrame = false;
                            if (debugTransform)
                                System.out.println("[autostack]     generating loop next push at line " + lastLine);
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
                        mv.visitFrame(F_APPEND, 1, new Object[] {MEMORYSTACK}, 0, null);
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
                        mv.visitMaxs(maxStack + (debugRuntime ? 2 : 1), maxLocals + 1);
                    }
                };
                return mv;
            }
        }, 0);
        byte[] arr = cw.toByteArray();
        return arr;
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException(t);
        }
    }
}
