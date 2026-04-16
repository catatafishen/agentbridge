import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Strips unused native libraries from sqlite-jdbc JAR.
 * <p>
 * sqlite-jdbc bundles 24 platform/architecture combinations (~14 MB).
 * This utility keeps only:
 * - Linux amd64 (x86_64)
 * - macOS aarch64
 * - Windows x86_64
 * <p>
 * All other native libraries are removed, typically saving ~10 MB.
 */
public class SqliteJarStripper {

    public void strip(File inputJar, File outputJar) throws IOException {
        File parentDir = outputJar.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create output directory: " + parentDir);
        }

        long originalSize;
        long strippedSize;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(inputJar));
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputJar))) {

            originalSize = inputJar.length();

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryPath = entry.getName();

                // Keep entries that are either:
                // 1. Not native libraries (don't start with org/sqlite/native/)
                // 2. Supported platform native libraries
                boolean keep = !entryPath.startsWith("org/sqlite/native/") ||
                    entryPath.contains("Linux/amd64/") ||
                    entryPath.contains("Mac/aarch64/") ||
                    entryPath.contains("Windows/x86_64/");

                if (keep) {
                    ZipEntry newEntry = new ZipEntry(entryPath);
                    newEntry.setTime(entry.getTime());
                    newEntry.setCompressedSize(-1);

                    zos.putNextEntry(newEntry);

                    if (!entry.isDirectory()) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = zis.read(buffer)) != -1) {
                            zos.write(buffer, 0, bytesRead);
                        }
                    }

                    zos.closeEntry();
                }
            }
        }

        strippedSize = outputJar.length();
        long savedMB = (originalSize - strippedSize) / (1024 * 1024);
        long originalMB = originalSize / (1024 * 1024);
        long strippedMB = strippedSize / (1024 * 1024);

        System.out.printf("SQLite JAR stripped: %dMB → %dMB (−%dMB)%n", originalMB, strippedMB, savedMB);
    }
}
