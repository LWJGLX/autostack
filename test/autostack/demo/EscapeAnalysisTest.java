package autostack.demo;

import org.lwjgl.vulkan.VkClearValue;

import autostack.UseCallerStack;

/**
 * Avoid the need to specify {@link UseCallerStack} when a method returns stack-allocated memory.
 */
public class EscapeAnalysisTest {

    public static VkClearValue callee() {
        // callee should not create a new stack frame since
        // stack-allocated memory escapes the method.
        return VkClearValue.callocStack();
    }

    public static void caller() {
        // callee should use caller's stack to allocate
        VkClearValue clearValue = callee();
    }

}
