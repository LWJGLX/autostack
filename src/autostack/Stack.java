package autostack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import org.lwjgl.PointerBuffer;

public class Stack {

    public static IntBuffer stackMallocInt(int size) {
        /* Will be transformed anyway */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    public static PointerBuffer stackMallocPointer(int size) {
        /* Will be transformed anyway */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    public static LongBuffer stackMallocLong(int size) {
        /* Will be transformed anyway */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    public static FloatBuffer stackMallocFloat(int size) {
        /* Will be transformed anyway */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    public static IntBuffer stackCallocInt(int size) {
        /* Will be transformed anyway */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    public static PointerBuffer stackCallocPointer(int size) {
        /* Will be transformed anyway */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    public static LongBuffer stackCallocLong(int size) {
        /* Will be transformed anyway */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

    public static FloatBuffer stackCallocFloat(int size) {
        /* Will be transformed anyway */
        throw new AssertionError("Please start the JVM with -javaagent:autostack.jar");
    }

}
