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

    public static void main(String[] args) throws IOException {
        if (args == null || args.length != 2) {
            System.out.println("Usage: java -jar autostack.jar input.jar output.jar");
            System.exit(1);
            return;
        }
        File inFile = new File(args[0]);
        File outFile = new File(args[1]);
        AutostackAgent agent = new AutostackAgent();

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
                    byte[] transformed = agent.transform(null, entry.getName().substring(0, entry.getName().length() - 6), null, null, classfileBytes);
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
