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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.AnnotationVisitor;
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

    private List<String> packages;
    private boolean debugTransform;
    private boolean debugRuntime;
    private boolean trace;
    private boolean defaultNewStack = true;
    private boolean checkStack;

    public Transformer(List<String> packages) {
        this.packages = packages != null ? packages : Collections.<String>emptyList();
    }

    public boolean isCheckStack() {
        return checkStack;
    }

    public void setCheckStack(boolean checkStack) {
        this.checkStack = checkStack;
    }

    public boolean isDefaultNewStack() {
        return defaultNewStack;
    }

    public void setDefaultNewStack(boolean defaultNewStack) {
        if (debugTransform)
            if (defaultNewStack)
                System.out.println("[autostack] default to creating new stack for each method in every transformed class");
            else
                System.out.println("[autostack] default to reusing caller stack for each method in every transformed class");
        this.defaultNewStack = defaultNewStack;
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
        try {
        if (className == null
                || className.startsWith("java/")
                || className.startsWith("sun/")
                || className.startsWith("org/lwjgl/"))
            return null;
        for (String pack : packages)
            if (!className.startsWith(pack))
                return null;
        ClassReader cr = new ClassReader(classfileBuffer);
        final Map<String, Integer> stackMethods = new HashMap<String, Integer>();
        // Scan all methods that need auto-stack
        if (debugTransform)
            System.out.println("[autostack] scanning methods in class: " + className.replace('/', '.'));
        cr.accept(new ClassVisitor(ASM5) {
            public MethodVisitor visitMethod(final int access, final String methodName, final String methodDesc, String signature, String[] exceptions) {
                MethodVisitor mv = new MethodVisitor(ASM5) {
                    boolean mark, catches, notransform;

                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if ("Lautostack/NoTransform;".equals(desc))
                            notransform = true;
                        return null;
                    }

                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == INVOKESTATIC && !itf && (
                                owner.startsWith("org/lwjgl/") && (name.equals("mallocStack") ||name.equals("callocStack")) ||
                                owner.equals(MEMORYSTACK) && (name.equals("stackGet") || name.equals("stackPop") || name.equals("stackPush") ||
                                                              name.startsWith("stackMalloc") || name.startsWith("stackCalloc") ||
                                                              name.equals("stackFloats")))) {
                            mark = true;
                        }
                    }

                    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        catches = true;
                    }

                    public void visitEnd() {
                        int flag = 0;
                        if (mark || notransform) {
                            if (notransform) {
                                flag |= 2;
                                if (debugTransform)
                                    System.out.println("[autostack]   will not transform method: " + className.replace('/', '.') + "." + methodName);
                            } else {
                                if (checkStack)
                                    flag |= 4;
                                flag |= catches ? 1 : 0;
                                if (debugTransform)
                                    System.out.println("[autostack]   will transform method: " + className.replace('/', '.') + "." + methodName);
                            }
                            stackMethods.put(methodName + methodDesc, Integer.valueOf(flag));
                        }
                    }
                };
                return mv;
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (stackMethods.isEmpty())
            return null;

        // Now, transform all such methods
        if (debugTransform)
            System.out.println("[autostack] transforming methods in class: " + className.replace('/', '.'));
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(ASM5, cw) {
            boolean classDefaultNewStack = defaultNewStack;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                cv.visit(version, access, name, signature, superName, interfaces);
                if (!checkStack) {
                    return;
                }
                /* Generate simple synthetic "compare stack pointers and throw if not equal" method */
                MethodVisitor mv = cv.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "$checkStack$", "(II)V", null, new String[] {"java/lang/AssertionError"});
                {
                    mv.visitCode();
                    mv.visitVarInsn(ILOAD, 0);
                    mv.visitVarInsn(ILOAD, 1);
                    Label l0 = new Label();
                    mv.visitJumpInsn(IF_ICMPEQ, l0);
                    mv.visitTypeInsn(NEW, "java/lang/IllegalStateException");
                    mv.visitInsn(DUP);
                    mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
                    mv.visitInsn(DUP);
                    mv.visitLdcInsn("Stack pointers differ: ");
                    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                    mv.visitVarInsn(ILOAD, 0);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
                    mv.visitLdcInsn(" != ");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                    mv.visitVarInsn(ILOAD, 1);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
                    mv.visitInsn(ATHROW);
                    mv.visitLabel(l0);
                    mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    mv.visitInsn(RETURN);
                    mv.visitMaxs(5, 2);
                    mv.visitEnd();
                }

                mv = cv.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "$checkStackWithThrowable$", "(Ljava/lang/Throwable;II)Ljava/lang/Throwable;", null, null);
                {
                    mv.visitCode();
                    mv.visitVarInsn(ILOAD, 1);
                    mv.visitVarInsn(ILOAD, 2);
                    Label l0 = new Label();
                    mv.visitJumpInsn(IF_ICMPEQ, l0);
                    mv.visitTypeInsn(NEW, "java/lang/IllegalStateException");
                    mv.visitInsn(DUP);
                    mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
                    mv.visitInsn(DUP);
                    mv.visitLdcInsn("Stack pointers differ: ");
                    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                    mv.visitVarInsn(ILOAD, 1);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
                    mv.visitLdcInsn(" != ");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                    mv.visitVarInsn(ILOAD, 2);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;", false);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;Ljava/lang/Throwable;)V", false);
                    mv.visitInsn(ARETURN);
                    mv.visitLabel(l0);
                    mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitInsn(ARETURN);
                    mv.visitMaxs(5, 3);
                    mv.visitEnd();
                }
            }

            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if ("Lautostack/UseCallerStack;".equals(desc)) {
                    if (debugTransform)
                        System.out.println("[autostack]   class declares to use caller stack for all methods, unless overridden by method");
                    classDefaultNewStack = false;
                    return null;
                } else if ("Lautostack/UseNewStack;".equals(desc)) {
                    if (debugTransform)
                        System.out.println("[autostack]   class declares to use new stack for all methods, unless overridden by method");
                    classDefaultNewStack = true;
                    return null;
                }
                return cv.visitAnnotation(desc, visible);
            }

            public MethodVisitor visitMethod(final int access, final String name, final String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                Integer info = stackMethods.get(name + desc);
                if (info == null || "<init>".equals(name) || "<clinit>".equals(name))
                    return mv;
                boolean catches = (info.intValue() & 1) == 1;
                if (debugTransform)
                    System.out.println("[autostack]   transform method: " + className.replace('/', '.') + "." + name);
                if (catches)
                    mv = new TryCatchBlockSorter(mv, access, name, desc, signature, exceptions);
                final Type[] paramTypes = Type.getArgumentTypes(desc);
                final boolean isStatic = (access & ACC_STATIC) != 0;
                final boolean notransform = (info.intValue() & 2) == 2;
                mv = new MethodVisitor(ASM5, mv) {
                    Label tryLabel = new Label();
                    Label finallyLabel = new Label();
                    int lastLine = 0;
                    boolean newStack = classDefaultNewStack;
                    int stackVarIndex;
                    int stackPointerVarIndex;
                    int firstAdditionalLocal;
                    int additionalLocals;
                    int moreStack;
                    Object[] replacedLocals;

                    public void visitInsn(int opcode) {
                        if (notransform) {
                            mv.visitInsn(opcode);
                            return;
                        }
                        if (opcode >= IRETURN && opcode <= RETURN && (newStack || checkStack)) {
                            if (debugRuntime && newStack && !checkStack) {
                                mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitLdcInsn("[autostack] restore stack pointer because of return at " + className.replace('/', '.') + "." + name + ":" + lastLine);
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                            }
                            if (newStack && !checkStack) {
                                mv.visitVarInsn(ALOAD, stackVarIndex);
                                mv.visitVarInsn(ILOAD, stackPointerVarIndex);
                                mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "setPointer", "(I)V", false);
                            } else if (checkStack) {
                                mv.visitVarInsn(ILOAD, stackPointerVarIndex);
                                mv.visitVarInsn(ALOAD, stackVarIndex);
                                mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "getPointer", "()I", false);
                                mv.visitMethodInsn(INVOKESTATIC, className, "$checkStack$", "(II)V", false);
                            }
                        }
                        mv.visitInsn(opcode);
                    }

                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if ("Lautostack/UseCallerStack;".equals(desc)) {
                            if (!notransform) {
                                if (debugTransform)
                                    System.out.println("[autostack]     method declares to use caller stack");
                                newStack = false;
                            }
                            return null;
                        } else if ("Lautostack/UseNewStack;".equals(desc)) {
                            if (!notransform) {
                                if (debugTransform)
                                    System.out.println("[autostack]     method declares to use new stack");
                                newStack = true;
                            }
                            return null;
                        } else if ("Lautostack/NoTransform;".equals(desc)) {
                            return null;
                        }
                        return mv.visitAnnotation(desc, visible);
                    }

                    public void visitVarInsn(int opcode, int var) {
                        if (notransform) {
                            mv.visitVarInsn(opcode, var);
                            return;
                        }
                        if (var >= firstAdditionalLocal)
                            var += additionalLocals;
                        mv.visitVarInsn(opcode, var);
                    }

                    public void visitIincInsn(int var, int increment) {
                        if (notransform) {
                            mv.visitIincInsn(var, increment);
                            return;
                        }
                        if (var >= firstAdditionalLocal)
                            var += additionalLocals;
                        mv.visitIincInsn(var, increment);
                    }

                    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
                        if (notransform) {
                            mv.visitFrame(type, nLocal, local, nStack, stack);
                            return;
                        }
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
                        if (notransform) {
                            mv.visitLocalVariable(className, desc, signature, start, end, index);
                            return;
                        }
                        if (index >= firstAdditionalLocal)
                            index += additionalLocals;
                        mv.visitLocalVariable(name, desc, signature, start, end, index);
                    }

                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode != INVOKESTATIC || notransform || checkStack) {
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
                        } else if (owner.equals(MEMORYSTACK) && name.equals("stackFloats")) {
                            Type[] argTypes = Type.getArgumentTypes(desc);
                            mv.visitVarInsn(ALOAD, stackVarIndex);
                            if (argTypes.length == 1 && argTypes[0].getSort() == Type.ARRAY) {
                                mv.visitInsn(SWAP);
                                mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "floats", desc, itf);
                            } else if (argTypes.length == 1) {
                                mv.visitInsn(SWAP);
                                mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "floats", desc, itf);
                            } else if (argTypes.length == 2) {
                                mv.visitInsn(DUP_X2);
                                mv.visitInsn(POP);
                                mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "floats", desc, itf);
                            } else if (argTypes.length == 3) {
                                mv.visitInsn(DUP2_X2);
                                mv.visitInsn(POP);
                                mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "floats", desc, itf);
                                mv.visitInsn(SWAP);
                                mv.visitInsn(POP);
                                moreStack = 1;
                            } else {
                                /* Give up. Not possible without an additional local */
                                mv.visitMethodInsn(INVOKESTATIC, MEMORYSTACK, "stackFloats", desc, itf);
                            }
                        } else {
                            mv.visitMethodInsn(opcode, owner, name, desc, itf);
                        }
                    }

                    public void visitLineNumber(int line, Label start) {
                        mv.visitLineNumber(line, start);
                        lastLine = line;
                    }

                    public void visitCode() {
                        if (notransform) {
                            mv.visitCode();
                            return;
                        }
                        additionalLocals = newStack || checkStack ? 2 : 1;
                        replacedLocals = new Object[paramTypes.length + additionalLocals + (isStatic ? 0 : 1)];
                        if (!newStack && !checkStack) {
                            replacedLocals[replacedLocals.length - 1] = MEMORYSTACK;
                        } else {
                            replacedLocals[replacedLocals.length - 2] = MEMORYSTACK;
                            replacedLocals[replacedLocals.length - 1] = INTEGER;
                        }
                        if (!isStatic)
                            replacedLocals[0] = className;
                        int var = isStatic ? 0 : 1;
                        for (int t = 0, i = var; t < paramTypes.length; t++, i++) {
                            Type type = paramTypes[t];
                            var += type.getSize();
                            switch (type.getSort()) {
                            case Type.INT:
                            case Type.BYTE:
                            case Type.BOOLEAN:
                            case Type.SHORT:
                            case Type.CHAR:
                                replacedLocals[i] = INTEGER;
                                break;
                            case Type.LONG:
                                replacedLocals[i] = LONG;
                                break;
                            case Type.FLOAT:
                                replacedLocals[i] = FLOAT;
                                break;
                            case Type.DOUBLE:
                                replacedLocals[i] = DOUBLE;
                                break;
                            case Type.OBJECT:
                            case Type.ARRAY:
                                replacedLocals[i] = type.getInternalName();
                                break;
                            default:
                                throw new AssertionError("Unhandled parameter type: " + type);
                            }
                        }
                        firstAdditionalLocal = var;
                        stackVarIndex = var;
                        stackPointerVarIndex = var + 1;
                        mv.visitCode();
                        if (newStack && !checkStack || checkStack) {
                            mv.visitMethodInsn(INVOKESTATIC, MEMORYSTACK, "stackGet", "()L"+ MEMORYSTACK + ";", false);
                            mv.visitInsn(DUP);
                            mv.visitVarInsn(ASTORE, stackVarIndex);
                            mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "getPointer", "()I", false);
                            mv.visitVarInsn(ISTORE, stackPointerVarIndex);
                            if (debugRuntime && newStack && !checkStack) {
                                mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitLdcInsn("[autostack] save stack pointer [");
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false);
                                mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitVarInsn(ILOAD, stackPointerVarIndex);
                                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false);
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false);
                                mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitLdcInsn("] at begin of " + className.replace('/', '.') + "." + name);
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                            }
                            mv.visitLabel(tryLabel);
                            mv.visitFrame(F_APPEND, 2, new Object[] {MEMORYSTACK, INTEGER}, 0, null);
                        } else if (!newStack && !checkStack) {
                            mv.visitMethodInsn(INVOKESTATIC, MEMORYSTACK, "stackGet", "()L"+ MEMORYSTACK + ";", false);
                            mv.visitVarInsn(ASTORE, stackVarIndex);
                            if (debugRuntime) {
                                mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitLdcInsn("[autostack] current stack pointer is [");
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false);
                                mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitVarInsn(ALOAD, stackVarIndex);
                                mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "getPointer", "()I", false);
                                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false);
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "print", "(Ljava/lang/String;)V", false);
                                mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitLdcInsn("] at begin of " + className.replace('/', '.') + "." + name);
                                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                            }
                            mv.visitLabel(tryLabel);
                            mv.visitFrame(F_APPEND, 1, new Object[] {MEMORYSTACK}, 0, null);
                        }
                    }

                    public void visitMaxs(int maxStack, int maxLocals) {
                        if (notransform) {
                            mv.visitMaxs(maxStack, maxLocals);
                            return;
                        }
                        if (newStack && !checkStack || checkStack) {
                            mv.visitLabel(finallyLabel);
                            mv.visitFrame(F_FULL, replacedLocals.length, replacedLocals, 1, new Object[] {"java/lang/Throwable"});
                            mv.visitTryCatchBlock(tryLabel, finallyLabel, finallyLabel, null);
                            if (debugRuntime && newStack && !checkStack) {
                                mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                                mv.visitLdcInsn("[autostack] restore stack pointer because of throw [");
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
                            if (newStack && !checkStack) {
                                mv.visitVarInsn(ALOAD, stackVarIndex);
                                mv.visitVarInsn(ILOAD, stackPointerVarIndex);
                                mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "setPointer", "(I)V", false);
                            }
                            if (checkStack) {
                                mv.visitVarInsn(ILOAD, stackPointerVarIndex);
                                mv.visitVarInsn(ALOAD, stackVarIndex);
                                mv.visitMethodInsn(INVOKEVIRTUAL, MEMORYSTACK, "getPointer", "()I", false);
                                mv.visitMethodInsn(INVOKESTATIC, className, "$checkStackWithThrowable$", "(Ljava/lang/Throwable;II)Ljava/lang/Throwable;", false);
                            }
                            mv.visitInsn(ATHROW);
                        }
                        mv.visitMaxs(maxStack + (debugRuntime ? 2 : 1) + moreStack, maxLocals + additionalLocals);
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
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException(t);
        }
    }
}
