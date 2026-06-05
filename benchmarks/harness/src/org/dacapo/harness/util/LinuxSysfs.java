package org.dacapo.harness.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// Reference: https://docs.kernel.org/filesystems/sysfs.html
public final class LinuxSysfs {
    public static final Charset CHARSET = StandardCharsets.US_ASCII;

    private LinuxSysfs() {
    }

    public static int readInt(Path path) throws IOException, NumberFormatException {
        return Integer.parseInt(readLine(path));
    }

    public static long readLong(Path path) throws IOException, NumberFormatException {
        return Long.parseLong(readLine(path));
    }

    public static String readLine(Path path) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(path, LinuxSysfs.CHARSET)) {
            return in.readLine();
        }
    }
}
