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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public class Tool {

    private static boolean DEBUG_TRANSFORM = getBooleanProperty("autostack.DEBUG_TRANSFORM", false);
    private static boolean DEBUG_RUNTIME = getBooleanProperty("autostack.DEBUG_RUNTIME", false);

    private static boolean getBooleanProperty(String prop, boolean def) {
        String value = System.getProperty(prop);
        if (value != null)
            return value.equals("") || Boolean.valueOf(value);
        return def;
    }

    public static void main(String[] args) throws IOException {
        if (args == null || args.length != 2) {
            System.out.println("Usage: java -jar autostack.jar input.jar output.jar");
            System.exit(1);
            return;
        }
        File inFile = new File(args[0]);
        File outFile = new File(args[1]);
        Transformer transformer = new Transformer("", DEBUG_TRANSFORM, DEBUG_RUNTIME);

        FileInputStream fis = new FileInputStream(inFile);
        JarInputStream jarIn = new JarInputStream(fis);

        FileOutputStream fos = new FileOutputStream(outFile);
        JarOutputStream jarOut = new JarOutputStream(fos, jarIn.getManifest());
        jarOut.setLevel(9);

        ZipEntry entry = jarIn.getNextEntry();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (entry != null) {
            ZipEntry outEntry = new ZipEntry(entry.getName());
            outEntry.setTime(entry.getTime());
            long size = entry.getSize();
            if (size >= 0L) {
                outEntry.setSize(entry.getSize());
            }
            jarOut.putNextEntry(outEntry);
            if (!entry.isDirectory()) {
                if (entry.getName().endsWith(".class")) {
                    baos.reset();
                    int read = 0;
                    byte[] arr = new byte[1024];
                    while ((read = jarIn.read(arr, 0, 1024)) != -1) {
                        baos.write(arr, 0, read);
                    }
                    byte[] classfileBytes = baos.toByteArray();
                    byte[] transformed = transformer.transform(null, entry.getName().substring(0, entry.getName().length() - 6), null, null, classfileBytes);
                    if (transformed != null) {
                        jarOut.write(transformed, 0, transformed.length);
                    } else {
                        jarOut.write(classfileBytes, 0, classfileBytes.length);
                    }
                } else {
                    int read = 0;
                    byte[] arr = new byte[1024];
                    while ((read = jarIn.read(arr, 0, 1024)) != -1) {
                        jarOut.write(arr, 0, read);
                    }
                }
            }
            jarOut.closeEntry();
            entry = jarIn.getNextEntry();
        }
        jarOut.finish();
        jarOut.flush();
        jarOut.close();
        fos.close();
        jarIn.close();
        fis.close();
    }

}
