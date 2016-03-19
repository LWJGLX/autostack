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

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

/**
 * Provides static access to stack allocation methods of {@link MemoryStack}.
 * <p>
 * Invocations to methods in this class will transform to instance method invocations on the {@link MemoryStack}.
 * 
 * @author Kai Burjack
 */
public class Stack {

    /**
     * Will be transformed to {@link MemoryStack#malloc(int)}
     */
    public static ByteBuffer mallocStack(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#mallocShort(int)}
     */
    public static ShortBuffer mallocStackShort(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#mallocInt(int)}
     */
    public static IntBuffer mallocStackInt(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#mallocPointer(int)}
     */
    public static PointerBuffer mallocStackPointer(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#mallocLong(int)}
     */
    public static LongBuffer mallocStackLong(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#mallocFloat(int)}
     */
    public static FloatBuffer mallocStackFloat(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#mallocDouble(int)}
     */
    public static DoubleBuffer mallocStackDouble(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#calloc(int)}
     */
    public static ByteBuffer callocStack(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#callocShort(int)}
     */
    public static ShortBuffer callocStackShort(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#callocInt(int)}
     */
    public static IntBuffer callocStackInt(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#callocPointer(int)}
     */
    public static PointerBuffer callocStackPointer(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#callocLong(int)}
     */
    public static LongBuffer callocStackLong(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#callocFloat(int)}
     */
    public static FloatBuffer callocStackFloat(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    /**
     * Will be transformed to {@link MemoryStack#callocDouble(int)}
     */
    public static DoubleBuffer callocStackDouble(int size) {
        /* Throw exception if not transformed by agent */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

}
