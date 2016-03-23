package autostack.demo;

import org.lwjgl.vulkan.VkClearValue;

import autostack.UseCallerStack;

/**
 * Avoid the need to specify {@link UseCallerStack} when a method returns stack-allocated memory.
 */
public class EscapeAnalysisTest {

    static class A {
        float a;
        VkClearValue b;
    }

    public static A callee() {
        VkClearValue struct = VkClearValue.callocStack();
        A a = new A();
        a.a = 4.0f;
        a.b = struct;
        return a;
    }

    public static void main(String[] args) {
        callee();
    }

}
