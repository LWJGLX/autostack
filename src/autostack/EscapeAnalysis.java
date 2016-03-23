package autostack;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * This is an implementation of <a href="http://www.cc.gatech.edu/~harrold/6340/cs6340_fall2009/Readings/choi99escape.pdf">Escape Analysis for Java</a> using the ASM Bytecode library.
 * <p>
 * It searches for all stack allocations using designated LWJGL 3 methods and determines whether that memory escapes the method.
 * 
 * @author Kai Burjack
 */
public class EscapeAnalysis implements Opcodes {

    static final String MEMORYSTACK = "org/lwjgl/system/MemoryStack";

    static class Edge {
        static final int TYPE_POINTS_TO = 1;
        static final int TYPE_DEFERRED = 2;

        int type;

        Node from;
        Node to;
        String fieldOwner;
        String fieldName;

        Edge(Node from, Node to, int type) {
            this.from = from;
            this.to = to;
            this.type = type;
        }
    }

    static class Node {
        static final int TYPE_OBJECT = 1;
        static final int TYPE_REFERENCE = 2;
        static final int TYPE_FIELD = 3;
        static final int TYPE_GLOBAL = 4;
        static final int TYPE_STACK = 5;

        static final int ESCAPE_GLOBAL = 1;
        static final int ESCAPE_ARG = 2;
        static final int ESCAPE_NO = 3;

        int type;
        int escapeState;
        boolean phantom;

        final List<Edge> incomings = new ArrayList<Edge>(4);
        final List<Edge> outgoings = new ArrayList<Edge>(4);

        Node(boolean phantom) {
            this.phantom = phantom;
        }

        void addDeferred(Node to) {
            Edge e = new Edge(this, to, Edge.TYPE_DEFERRED);
            this.outgoings.add(e);
            to.incomings.add(e);
        }

        void addPointsTo(Node to) {
            Edge e = new Edge(this, to, Edge.TYPE_POINTS_TO);
            this.outgoings.add(e);
            to.incomings.add(e);
        }
    }

    public Set<String> stackEscapingMethods(ClassReader cr, final ClassLoader cl) {
        final Set<String> escapingMethods = new HashSet<String>();
        cr.accept(new ClassVisitor(ASM5) {
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if ("<init>".equals(name) || "<clinit>".equals(name)) {
                    return null;
                }
                final Node[] locals = new Node[256];
                Type[] paramTypes = Type.getArgumentTypes(desc);
                int idx = 0;
                final Node unescapable = new Node(false);
                if ((access & ACC_STATIC) == 0) {
                    Node thiz = new Node(false);
                    thiz.escapeState = Node.ESCAPE_ARG;
                    locals[idx++] = thiz;
                }
                for (int i = 0; i < paramTypes.length; i++) {
                    Type t = paramTypes[i];
                    if (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY) {
                        Node n = new Node(false);
                        n.escapeState = Node.ESCAPE_ARG;
                        locals[idx++] = n;
                    } else if (t.getSort() == Type.LONG || t.getSort() == Type.DOUBLE) {
                        locals[idx++] = unescapable;
                        locals[idx++] = unescapable;
                    } else {
                        locals[idx++] = unescapable;
                    }
                }

                return new MethodVisitor(ASM5) {
                    Node returnNode = new Node(true);
                    Node stackNode = new Node(true);
                    Node nullValue = new Node(true);
                    Stack<Node> operandStack = new Stack<Node>();

                    public void visitInsn(int opcode) {
                        switch (opcode) {
                        case ACONST_NULL:
                            operandStack.push(nullValue);
                            break;
                        case ICONST_M1:
                        case ICONST_0:
                        case ICONST_1:
                        case ICONST_2:
                        case ICONST_3:
                        case ICONST_4:
                        case ICONST_5:
                            operandStack.push(unescapable);
                            break;
                        case LCONST_0:
                        case LCONST_1:
                            operandStack.push(unescapable);
                            operandStack.push(unescapable);
                            break;
                        case FCONST_0:
                        case FCONST_1:
                        case FCONST_2:
                            operandStack.push(unescapable);
                            break;
                        case DCONST_0:
                        case DCONST_1:
                            operandStack.push(unescapable);
                            operandStack.push(unescapable);
                            break;
                        case IALOAD:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.push(unescapable);
                            break;
                        case LALOAD:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.push(unescapable);
                            operandStack.push(unescapable);
                            break;
                        case FALOAD:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.push(unescapable);
                            break;
                        case DALOAD:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.push(unescapable);
                            operandStack.push(unescapable);
                            break;
                        case AALOAD:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.push(new Node(false));
                            break;
                        case BALOAD:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.push(unescapable);
                            break;
                        case CALOAD:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.push(unescapable);
                            break;
                        case SALOAD:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.push(unescapable);
                            break;
                        case IASTORE:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case LASTORE:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case FASTORE:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case DASTORE:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case AASTORE:
                        case BASTORE:
                        case CASTORE:
                        case SASTORE:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case POP:
                            operandStack.pop();
                            break;
                        case POP2:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case DUP:
                            operandStack.push(operandStack.peek());
                            break;
                        case DUP_X1:
                            throw new AssertionError("NYI");
                        case DUP_X2:
                            throw new AssertionError("NYI");
                        case DUP2: {
                            Node n1 = operandStack.pop();
                            Node n2 = operandStack.pop();
                            operandStack.push(n2);
                            operandStack.push(n1);
                            operandStack.push(n2);
                            operandStack.push(n1);
                            break;
                        }
                        case DUP2_X1:
                            throw new AssertionError("NYI");
                        case DUP2_X2:
                            throw new AssertionError("NYI");
                        case SWAP: {
                            Node n1 = operandStack.pop();
                            Node n2 = operandStack.pop();
                            operandStack.push(n1);
                            operandStack.push(n2);
                            break;
                        }
                        case IADD:
                            operandStack.pop();
                            break;
                        case LADD:
                            break;
                        case FADD:
                            operandStack.pop();
                            break;
                        case DADD:
                            break;
                        case ISUB:
                            operandStack.pop();
                            break;
                        case LSUB:
                            break;
                        case FSUB:
                            operandStack.pop();
                            break;
                        case DSUB:
                            break;
                        case IMUL:
                            operandStack.pop();
                            break;
                        case LMUL:
                            break;
                        case FMUL:
                            operandStack.pop();
                            break;
                        case DMUL:
                            break;
                        case IDIV:
                            operandStack.pop();
                            break;
                        case LDIV:
                            break;
                        case FDIV:
                            operandStack.pop();
                            break;
                        case DDIV:
                            break;
                        case IREM:
                            operandStack.pop();
                            break;
                        case LREM:
                            break;
                        case FREM:
                            operandStack.pop();
                            break;
                        case DREM:
                            break;
                        case INEG:
                        case LNEG:
                        case FNEG:
                        case DNEG:
                            break;
                        case ISHL:
                            operandStack.pop();
                            break;
                        case LSHL:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case ISHR:
                            operandStack.pop();
                            break;
                        case LSHR:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case IUSHR:
                            operandStack.pop();
                            break;
                        case LUSHR:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case IAND:
                            operandStack.pop();
                            break;
                        case LAND:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case IOR:
                            operandStack.pop();
                            break;
                        case LOR:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case IXOR:
                            operandStack.pop();
                            break;
                        case LXOR:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case I2L:
                            operandStack.push(unescapable);
                            break;
                        case I2F:
                            break;
                        case I2D:
                            operandStack.push(unescapable);
                            break;
                        case L2I:
                            operandStack.pop();
                            break;
                        case L2F:
                            operandStack.pop();
                            break;
                        case L2D:
                        case F2I:
                            break;
                        case F2L:
                            operandStack.push(unescapable);
                            break;
                        case F2D:
                            break;
                        case D2I:
                            operandStack.pop();
                            break;
                        case D2L:
                            break;
                        case D2F:
                            operandStack.pop();
                            break;
                        case I2B:
                        case I2C:
                        case I2S:
                            break;
                        case LCMP:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case FCMPL:
                        case FCMPG:
                            operandStack.pop();
                            break;
                        case DCMPL:
                        case DCMPG:
                            operandStack.pop();
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case IRETURN:
                            operandStack.pop();
                            break;
                        case LRETURN:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case FRETURN:
                            operandStack.pop();
                            break;
                        case DRETURN:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case ARETURN:
                            // 4.2 Connection Graph at Method Exit
                            Node n = operandStack.pop();
                            returnNode.addDeferred(n);
                            break;
                        case RETURN:
                            break;
                        case ARRAYLENGTH:
                            operandStack.pop();
                            operandStack.push(unescapable);
                            break;
                        case ATHROW:
                            operandStack.pop();
                            break;
                        case MONITORENTER:
                        case MONITOREXIT:
                            operandStack.pop();
                            break;
                        default:
                            throw new AssertionError();
                        }
                    }

                    public void visitJumpInsn(int opcode, Label label) {
                        switch (opcode) {
                        case IFEQ:
                        case IFNE:
                        case IFLT:
                        case IFGE:
                        case IFGT:
                        case IFLE:
                            operandStack.pop();
                            break;
                        case IF_ICMPEQ:
                        case IF_ICMPNE:
                        case IF_ICMPLT:
                        case IF_ICMPGE:
                        case IF_ICMPGT:
                        case IF_ICMPLE:
                        case IF_ACMPEQ:
                        case IF_ACMPNE:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case GOTO:
                        case JSR:
                            break;
                        case IFNULL:
                            operandStack.pop();
                            break;
                        case IFNONNULL:
                            operandStack.pop();
                            break;
                        default:
                            throw new AssertionError();
                        }
                    }

                    public void visitLdcInsn(Object cst) {
                        if (cst instanceof Integer) {
                            operandStack.push(unescapable);
                        } else if (cst instanceof Float) {
                            operandStack.push(unescapable);
                        } else if (cst instanceof Long) {
                            operandStack.push(unescapable);
                            operandStack.push(unescapable);
                        } else if (cst instanceof Double) {
                            operandStack.push(unescapable);
                            operandStack.push(unescapable);
                        } else if (cst instanceof String) {
                            // Strings are considered primitives in our case, because we cannot
                            // escape memory into a string object
                            operandStack.push(unescapable);
                        } else if (cst instanceof Type) {
                            int sort = ((Type) cst).getSort();
                            if (sort == Type.OBJECT) {
                                operandStack.push(unescapable);
                            } else if (sort == Type.ARRAY) {
                                operandStack.push(unescapable);
                            } else if (sort == Type.METHOD) {
                                operandStack.push(unescapable);
                            } else {
                                throw new AssertionError("Illegal LDC: " + cst);
                            }
                        } else if (cst instanceof Handle) {
                            operandStack.push(unescapable);
                        } else {
                            throw new AssertionError("Illegal LDC: " + cst);
                        }
                    }

                    public void visitIntInsn(int opcode, int operand) {
                        if (opcode == BIPUSH || opcode == SIPUSH) {
                            operandStack.push(unescapable);
                        } else if (opcode == NEWARRAY) {
                            operandStack.push(new Node(false));
                        } else
                            throw new AssertionError();
                    }

                    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        Type fieldType = Type.getType(desc);
                        if (opcode == PUTSTATIC) {
                            // setting a static field globally escapes the node!
                            if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                                Node n = operandStack.pop();
                                n.escapeState = Node.ESCAPE_GLOBAL;
                            } else {
                                for (int i = 0; i < fieldType.getSize(); i++) {
                                    operandStack.pop();
                                }
                            }
                        } else if (opcode == PUTFIELD) {
                            if (fieldType.getSort() != Type.OBJECT && fieldType.getSort() != Type.ARRAY) {
                                for (int i = 0; i < fieldType.getSize(); i++) {
                                    operandStack.pop();
                                }
                                operandStack.pop();
                                return;
                            }
                            Node q = operandStack.pop();
                            // p.f = q (chapter "3 Interprocedural Analysis", page 5)
                            Node target = (opcode == PUTFIELD || opcode == GETFIELD) ? operandStack.pop() : null;
                            Node p = target;
                            int numPointsTo = 0;
                            for (Edge e : p.outgoings) {
                                if (e.type == Edge.TYPE_POINTS_TO)
                                    numPointsTo++;
                            }
                            if (numPointsTo == 0) {
                                Node phantomObject = new Node(true);
                                p.addPointsTo(phantomObject);
                            }
                            List<Node> V = new ArrayList<Node>();
                            for (Edge e : p.outgoings) {
                                if (e.type == Edge.TYPE_POINTS_TO && owner.equals(e.fieldOwner) && name.equals(e.fieldName))
                                    V.add(e.to);
                            }
                            if (V.isEmpty()) {
                                Node fieldRef = new Node(true);
                                fieldRef.type = Node.TYPE_FIELD;
                                Edge edge = new Edge(p, fieldRef, Edge.TYPE_POINTS_TO);
                                edge.fieldOwner = owner;
                                edge.fieldName = name;
                                p.outgoings.add(edge);
                                fieldRef.incomings.add(edge);
                                V.add(fieldRef);
                            }
                            for (Node v : V) {
                                v.addDeferred(q);
                            }
                        } else if (opcode == GETSTATIC || opcode == GETFIELD) {
                            // TODO: Implement p = q.f on page 6
                            if (fieldType.getSort() == Type.DOUBLE || fieldType.getSort() == Type.LONG) {
                                operandStack.push(unescapable);
                                operandStack.push(unescapable);
                            } else if (fieldType.getSort() == Type.OBJECT || fieldType.getSort() == Type.ARRAY) {
                                Node n = new Node(false);
                                n.escapeState = Node.ESCAPE_GLOBAL;
                                operandStack.push(n);
                            } else if (fieldType.getSort() != Type.VOID) {
                                operandStack.push(unescapable);
                            } else {
                                throw new AssertionError();
                            }
                        } else {
                            throw new AssertionError();
                        }
                    }

                    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                        operandStack.pop();
                    }

                    public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                        operandStack.pop();
                    }

                    public void visitTypeInsn(int opcode, String type) {
                        switch (opcode) {
                        case NEW:
                            operandStack.push(new Node(false));
                            break;
                        case ANEWARRAY:
                            operandStack.pop();
                            operandStack.push(new Node(false));
                            break;
                        case CHECKCAST:
                        case INSTANCEOF:
                            operandStack.pop();
                            operandStack.push(unescapable);
                            break;
                        default:
                            throw new AssertionError();
                        }
                    }

                    public void visitVarInsn(int opcode, int var) {
                        switch (opcode) {
                        case ILOAD:
                            operandStack.push(unescapable);
                            break;
                        case LLOAD:
                            operandStack.push(unescapable);
                            operandStack.push(unescapable);
                            break;
                        case FLOAD:
                            operandStack.push(unescapable);
                            break;
                        case DLOAD:
                            operandStack.push(unescapable);
                            operandStack.push(unescapable);
                            break;
                        case ALOAD:
                            operandStack.push(locals[var]);
                            break;
                        case ISTORE:
                            operandStack.pop();
                            break;
                        case LSTORE:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case FSTORE:
                            operandStack.pop();
                            break;
                        case DSTORE:
                            operandStack.pop();
                            operandStack.pop();
                            break;
                        case ASTORE:
                            locals[var] = operandStack.pop();
                            break;
                        default:
                            throw new AssertionError();
                        }
                    }

                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        if (opcode == INVOKESTATIC && (owner.startsWith("org/lwjgl/") && (name.equals("mallocStack") || name.equals("callocStack")))) {
                            // Struct allocation
                            Node stackMemory = new Node(false);
                            stackMemory.type = Node.TYPE_STACK;
                            operandStack.push(stackMemory);
                        } else if (owner.equals(MEMORYSTACK) && name.equals("stackGet")) {
                            // obtain stack
                            operandStack.push(stackNode);
                        } else if (owner.equals(MEMORYSTACK) && (name.equals("stackPush") || name.equals("stackPop"))) {
                            // stack push/pop
                            // don't care.
                        } else if (owner.equals(MEMORYSTACK) && (name.startsWith("stackMalloc") || name.startsWith("stackCalloc"))) {
                            // stack malloc/calloc
                            operandStack.pop();
                            // create a stack node
                            Node n = new Node(false);
                            n.type = Node.TYPE_STACK;
                            operandStack.push(n);
                        } else {
                            Type[] ts = Type.getArgumentTypes(desc);
                            Type retType = Type.getReturnType(desc);
                            List<Node> objArgs = new ArrayList<Node>();
                            for (int i = ts.length - 1; i >= 0; i--) {
                                Type t = ts[i];
                                Node argumentNode = operandStack.pop();
                                if (t.getSort() == Type.DOUBLE || t.getSort() == Type.LONG) {
                                    operandStack.pop();
                                }
                                if (t.getSort() >= Type.BOOLEAN && t.getSort() <= Type.DOUBLE || argumentNode == unescapable) {
                                    // Primitive types. Don't care
                                } else if (t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY) {
                                    objArgs.add(argumentNode);
                                }
                            }
                            Node target = opcode == INVOKESTATIC ? null : operandStack.pop();
                            boolean isStruct = false;
                            if (target != null && target.type == Node.TYPE_STACK && retType.getSort() == Type.OBJECT) {
                                // Check if it's a struct subclass without loading the class
                                try {
                                    InputStream is = cl.getResourceAsStream(owner + ".class");
                                    ClassReader lcr = new ClassReader(is);
                                    is.close();
                                    String superClass = lcr.getSuperName();
                                    isStruct = "org/lwjgl/system/Struct".equals(superClass);
                                } catch (IOException e) {
                                    // Class definition not found: Probably a runtime-generated class. So load it.
                                    try {
                                        Class<?> clazz = cl.loadClass(owner.replace('/', '.'));
                                        Class<?> superClass = clazz.getSuperclass();
                                        isStruct = "org.lwjgl.system.Struct".equals(superClass.getName());
                                    } catch (ClassNotFoundException ex) {
                                        throw new AssertionError(ex);
                                    }
                                }
                            }
                            if (isStruct) {
                                operandStack.push(target);
                            } else if (!isStruct) {
                                // All object arguments escape globally!
                                for (Node arg : objArgs) {
                                    arg.escapeState = Node.ESCAPE_GLOBAL;
                                }
                                if (retType.getSort() == Type.OBJECT || retType.getSort() == Type.ARRAY) {
                                    // Object return values from methods escape globally
                                    Node n = new Node(false);
                                    n.escapeState = Node.ESCAPE_GLOBAL;
                                    operandStack.push(new Node(false));
                                } else if (retType.getSort() == Type.LONG || retType.getSort() == Type.DOUBLE) {
                                    operandStack.push(unescapable);
                                    operandStack.push(unescapable);
                                } else if (retType.getSort() != Type.VOID) {
                                    operandStack.push(unescapable);
                                }
                            }
                        }
                    }

                    public void visitEnd() {
                        // TODO: Walk the graph to find escaping memory
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return escapingMethods;
    }

}
