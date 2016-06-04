What does it get me?
--------------------
LWJGL 3 has a nice stack allocation API. Autostack builds on top of this and provides automatic stack frame allocation/deallocation in methods that make use of LWJGL 3's MemoryStack class.
This means, with Autostack you:
- do not have to MemoryStack.stackPush() at the beginning of the method
- do not have to MemoryStack.pop() at the end of the method (including before any intermittent returns)
- do not have to take care of exception handling with probably a try-finally wrapping the whole method to ensure MemoryStack.pop() gets called eventually
- do not need to care about the optimal uses of MemoryStack.push() and MemoryStack.pop()
- do not need to care about performance issues when performing repeated thread-local lookups on the MemoryStack.stackGet()

For an example see the Vulkan [ClearScreenDemo](https://github.com/httpdigest/lwjgl3-autostack/blob/master/test/autostack/demo/ClearScreenDemoUseNewStack.java).

How to use it?
--------------
Start the JVM with the VM argument:

  `-javaagent:/path/to/autostack.jar`

Optionally, to have optimal loading time, use a package prefix to tell Autostack where to search for classes to transform:

  `-javaagent:/path/to/autostack.jar=my.app`

You can also filter by multiple packages/class prefixes:

  `-javaagent:/path/to/autostack.jar=my.app,my.other.app,another.app`

Controlling stack lifecycle
---------------------------

By default, every method will create its own stack frame and stack allocations done in those methods will not survive past the method invocation.
To avoid creating a new stack frame for a particular method, annotate that method with `@UseCallerStack`:
```Java
@UseCallerStack
public Struct allocateOnCallerStack() {
  return SomeStruct.callocStack()
                   .setSomeProperty(value)
                   .setSomeOtherProperty(otherValue);
}
```
This will result in the memory allocated for `SomeStruct` to be still available at every caller of this method.

You can also override the stack lifecycle for all methods in a particular class by annotating the class with `@UseCallerStack`. This will apply that behaviour to all methods declared by this class.

Build-time instrumentation
--------------------------
If for you the runtime instrumentation with the Java Agent is too slow or you don't want to have to provide the JVM argument or distribute the autostack.jar file with your application, you can also transform your classes offline.
For this, the autostack.jar is an executable jar itself, which can be used like this:

  `java -jar autostack.jar input.jar output.jar`

In this example the input.jar is the jar file containing your uninstrumented class files (and possibly any other resources of your application). The output.jar is the jar in which all applicable transformations have been applied.

Once the classes have been transformed using this offline tool, there is no dependency anymore to the autostack.jar, so it need not be inside the application's classpath at runtime.

I want to see what happens
--------------------------
If you want to see which methods will be transformed by the agent and what happens at runtime when the transformed methods execute, there are two JVM system property parameters, which log to standard out:
- `-Dautostack.DEBUG_TRANSFORM`: Logs the methods and invocations within those methods that get transformed.
- `-Dautostack.DEBUG_RUNTIME`: Logs whenever an automatic stack push/pop happens at runtime. This potentially results in a lot of logging at runtime!
- `-Dautostack.TRACE`: Logs the bytecode of each transformed class and method.

All properties apply to both the runtime agent and the build-time instrumentation tool. When used as JVM arguments to the offline jar transformation, both properties control the logging of the transformation as well as the code transformation to log during runtime of the application. So, with build-time instrumentation changing any of these properties at runtime of the application is not possible anymore. This is only possible with the runtime transformation agent.
