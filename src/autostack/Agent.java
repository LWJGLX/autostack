package autostack;

import java.lang.instrument.Instrumentation;

public class Agent {

    private static boolean DEBUG_TRANSFORM = getBooleanProperty("autostack.DEBUG_TRANSFORM", false);
    private static boolean DEBUG_RUNTIME = getBooleanProperty("autostack.DEBUG_RUNTIME", false);
    private static boolean TRACE = getBooleanProperty("autostack.TRACE", false);

    private static boolean getBooleanProperty(String prop, boolean def) {
        String value = System.getProperty(prop);
        if (value != null)
            return value.equals("") || Boolean.valueOf(value);
        return def;
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) {
        Transformer transformer = new Transformer(agentArguments);
        transformer.setDebugRuntime(DEBUG_RUNTIME);
        transformer.setDebugTransform(DEBUG_TRANSFORM);
        transformer.setTrace(TRACE);
        instrumentation.addTransformer(transformer);
    }

}
