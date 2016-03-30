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

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

public class Agent {

    private static boolean DEBUG_TRANSFORM = getBooleanProperty("autostack.DEBUG_TRANSFORM", false);
    private static boolean DEBUG_RUNTIME = getBooleanProperty("autostack.DEBUG_RUNTIME", false);
    private static boolean TRACE = getBooleanProperty("autostack.TRACE", false);
    private static boolean CHECK_STACK = getBooleanProperty("autostack.CHECK_STACK", false);
    private static boolean STACK_PARAM = getBooleanProperty("autostack.STACK_PARAM", false);

    private static boolean getBooleanProperty(String prop, boolean def) {
        String value = System.getProperty(prop);
        if (value != null)
            return value.equals("") || Boolean.valueOf(value);
        return def;
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) {
    	if (agentArguments == null)
    		agentArguments = "";
    	String[] splitted = agentArguments.split(",");
    	List<String> packs = new ArrayList<String>();
    	boolean defaultNewStack = true;
    	for (String s : splitted) {
    		if (s.startsWith("-")) {
    			if ("-usecallerstack".equals(s))
    				defaultNewStack = false;
    			else if ("-usenewstack".equals(s))
    				defaultNewStack = true;
    		}
    		else
    			packs.add(s.replace('.', '/'));
    	}
        Transformer transformer = new Transformer(packs);
        transformer.setDebugRuntime(DEBUG_RUNTIME);
        transformer.setDebugTransform(DEBUG_TRANSFORM);
        transformer.setTrace(TRACE);
        transformer.setDefaultNewStack(defaultNewStack);
        transformer.setCheckStack(CHECK_STACK);
        transformer.setStackAsParameter(STACK_PARAM);
        instrumentation.addTransformer(transformer);
    }

}
