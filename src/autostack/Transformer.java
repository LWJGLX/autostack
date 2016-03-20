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
import java.util.HashMap;
import java.util.Map;

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
    private static final String MEMORYSTACK = "org/lwjgl/system/MemoryStack";
    
    private String packageClassPrefix;
    private boolean debugTransform;
    private boolean debugRuntime;
    private boolean trace;

    public Transformer(String packageClassPrefix) {
        this.packageClassPrefix = packageClassPrefix != null ? packageClassPrefix.replace('.', '/') : "";
    }

    public boolean isDebugTransform() {
        return debugTransform;
    }

    public void setDebugTransform(boolean debugTransform) {
        this.debugTransform = debugTransform;
    }

    public boolean isDebugRuntime() {
        return debugRuntime;
    }

    public void setDebugRuntime(boolean debugRuntime) {
        this.debugRuntime = debugRuntime;
    }

    public boolean isTrace() {
        return trace;
    }

    public void setTrace(boolean trace) {
        this.trace = trace;
    }

    public byte[] transform(ClassLoader loader, final String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null
                || className.startsWith("java/")
                || className.startsWith("sun/")
                || className.startsWith("org/lwjgl/")
                || !className.startsWith(packageClassPrefix))
            return null;
        ClassReader cr = new ClassReader(classfileBuffer);
        final Map<String, Boolean> stackMethods = new HashMap<String, Boolean>();
        // Scan all methods that need auto-stack
        if (debugTransform)
            System.out.println("[autostack] Scan methods in class: " + className.replace('/', '.'));
        cr.accept(new ClassVisitor(ASM5) {
            public MethodVisitor visitMethod(final int access, final String methodName, final String methodDesc, String signature, String[] exceptions) {
                MethodVisitor mv = new MethodVisitor(ASM5) {
                    boolean mark, catches;

                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == INVOKESTATIC && !itf && (
                                owner.startsWith("org/lwjgl/") && (name.equals("mallocStack") ||name.equals("callocStack")) ||
                                owner.equals(MEMORYSTACK) && (name.equals("stackGet") || name.equals("stackPop") || name.equals("stackPush") ||
                                                              name.startsWith("stackAlloc") || name.startsWith("stackCalloc")))) {
                            mark = true;
                        }
                    }

                    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        catches = true;
                    }

                    public void visitEnd() {
                        if (mark) {
                            if (debugTransform)
                                System.out.println("[autostack]   Will transform method: " + className.replace('/', '.') + "." + methodName);
                            stackMethods.put(methodName + methodDesc, catches);
                        }
                    }
                };
                return mv;
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (stackMethods.isEmpty())
            return null;

        // Now, transform all such methods
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(ASM5, cw) {
            public MethodVisitor visitMethod(final int access, final String name, final String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                Boolean info = stackMethods.get(name + desc);
                if (info == null)
                    return mv;
                boolean catches = info.booleanValue();
                if (debugTransform)
                    System.out.println("[autostack]   Transforming method: " + className.replace('/', '.') + "." + name);
                if (catches)
                    mv = new TryCatchBlockSorter(mv, access, name, desc, signature, exceptions);
                Type[] paramTypes = Type.getArgumentTypes(desc);
                boolean isStatic = (access & ACC_STATIC) != 0;
                final int additionalLocals = 2;
                final Object[] replacedLocals = new Object[paramTypes.length + additionalLocals + (isStatic ? 0 : 1)];
                replacedLocals[replacedLocals.length - 2] = MEMORYSTACK;
                replacedLocals[replacedLocals.length - 1] = INTEGER;
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
                final int firstAdditionalLocal = var;
                final int stackVarIndex = var;
                final int stackPointerVarIndex = var + 1;
                mv = new MethodVisitor(ASM5, mv) {
                    Label tryLabel = new Label();
                    Label finallyLabel = new Label();
                    int lastLine = 0;

                    public void visitInsn(int opcode) {
                        if (opcode >= IRETURN && opcode <= RETURN) {
                            if (debugRuntime) {
                                mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitLdcInsn("[autostack] Pop stack because of return at " + className.replace('/', '.') + "." + name + ":" + lastLine);
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                            }
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            mv.visitVarInsn(ILOAD, stackPointerVarIndex);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "setPointer", "(I)V", false);
                        }
                        mv.visitInsn(opcode);
                    }

                    public void visitVarInsn(int opcode, int var) {
                        if (var >= firstAdditionalLocal)
                            var += additionalLocals;
                        mv.visitVarInsn(opcode, var);
                    }

                    public void visitIincInsn(int var, int increment) {
                        if (var >= firstAdditionalLocal)
                            var += additionalLocals;
                        mv.visitIincInsn(var, increment);
                    }

                    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
                        if (type == F_FULL) {
                            Object[] locals = new Object[local.length + additionalLocals];
                            int replacementLength = replacedLocals.length;
                            System.arraycopy(replacedLocals, 0, locals, 0, replacementLength);
                            int len = locals.length - replacementLength;
                            System.arraycopy(local, replacementLength - additionalLocals, locals, replacementLength, len);
                            mv.visitFrame(type, nLocal + additionalLocals, locals, nStack, stack);
                        } else
                            mv.visitFrame(type, nLocal, local, nStack, stack);
                    }

                    public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
                        if (index >= firstAdditionalLocal)
                            index += additionalLocals;
                        mv.visitLocalVariable(name, desc, signature, start, end, index);
                    }

                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode != INVOKESTATIC) {
                            mv.visitMethodInsn(opcode, owner, name, desc, itf);
                            return;
                        }
                        if (owner.startsWith("org/lwjgl/") && (name.equals("mallocStack") || name.equals("callocStack"))) {
                            if (debugTransform)
                                System.out.println("[autostack]     rewrite invocation of " + owner.replace('/', '.') + "." + name + " at line " + lastLine + " --> aload " + stackVarIndex + "; invokestatic " + owner.replace('/', '.') + "." + name);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            int paramEndIndex = desc.indexOf(')');
                            String beforeDesc = desc.substring(0, paramEndIndex);
                            String afterDesc = desc.substring(paramEndIndex);
                            mv.visitMethodInsn(opcode, owner, name, beforeDesc + "L" + MEMORYSTACK + ";" + afterDesc, false);
                        } else if (owner.equals(MEMORYSTACK) && name.equals("stackGet")) {
                            if (debugTransform)
                                System.out.println("[autostack]     rewrite invocation of " + owner.replace('/', '.') + "." + name + " at line " + lastLine + " --> aload " + stackVarIndex);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                        } else if (owner.equals(MEMORYSTACK) && (name.equals("stackPush") || name.equals("stackPop"))) {
                            String newName = "p" + name.substring(6);
                            if (debugTransform)
                                System.out.println("[autostack]     rewrite invocation of " + owner.replace('/', '.') + "." + name + " at line " + lastLine + " --> aload " + stackVarIndex + "; invokevirtual " + MEMORYSTACK.replace('/', '.') + "." + newName);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, newName, desc, itf);
                        } else if (owner.equals(MEMORYSTACK) && (name.startsWith("stackMalloc") || name.startsWith("stackCalloc"))) {
                            String newName = name.substring(5, 6).toLowerCase() + name.substring(6);
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
                        mv.visitMethodInsn(INVOKESTATIC, MEMORYSTACK, "stackGet", "()L"+ MEMORYSTACK + ";", false);
                        mv.visitInsn(DUP);
                        mv.visitVarInsn(ASTORE, stackVarIndex);
                        mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "getPointer", "()I", false);
                        mv.visitVarInsn(ISTORE, stackPointerVarIndex);
                        mv.visitLabel(tryLabel);
                        mv.visitFrame(F_APPEND, 2, new Object[] {MEMORYSTACK, INTEGER}, 0, null);
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
                        mv.visitVarInsn(ILOAD, stackPointerVarIndex);
                        mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "setPointer", "(I)V", false);
                        mv.visitInsn(ATHROW);
                        mv.visitMaxs(maxStack + (debugRuntime ? 2 : 1), maxLocals + additionalLocals);
                    }
                };
                return mv;
            }
        }, 0);
        byte[] arr = cw.toByteArray();
        if (trace) {
            cr = new ClassReader(arr);
            cr.accept(new TraceClassVisitor(new PrintWriter(System.out)), 0);
        }
        return arr;
    }
}
