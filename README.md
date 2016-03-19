What does it get me?
--------------------
LWJGL 3 has a nice stack allocation API. Autostack builds on top of this and provides automatic stack frame allocation/deallocation in methods that make use of LWJGL 3's MemoryStack class.
This means, with Autostack you:
- do not have to MemoryStack.stackPush() at the beginning of the method
- do not have to MemoryStack.pop() at the end of the method (including any intermittent returns)
- do not have to take care of exception handling with probably a try-finally wrapping the whole method to ensure MemoryStack.pop() gets called eventually
- do not need to care about the optimal uses of MemoryStack.push() and MemoryStack.pop()
- do not need to care about performance issues when performing repeated thread-local lookups on the MemoryStack.stackGet()

For an example see the Vulkan [ClearScreenDemo](https://github.com/httpdigest/lwjgl3-autostack/blob/master/test/org/lwjgl/demo/vulkan/ClearScreenDemo.java).

How to use it?
--------------
Start the JVM with the VM argument:

  `-javaagent:/path/to/autostack.jar`

Optionally, to have optimal loading time, use a package prefix to tell Autostack where to search for classes to transform:

  `-javaagent:/path/to/autostack.jar=my.package`

Build-time instrumentation
--------------------------
If for you the runtime instrumentation with the Java Agent is too slow or you don't want to have to provide the JVM argument or distribute the autostack.jar file with your application, you can also transform your classes offline.
For this, the autostack.jar is an executable jar itself, which can be used like this:

  `java -jar autotools.jar input.jar output.jar`

In this example the input.jar is the jar file containing your uninstrumented class files (and possibly any other resources of your application). The output.jar is the jar in which all applicable transformations have been applied.