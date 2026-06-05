package org.dacapo.proc.linux;

import org.dacapo.harness.util.AsciiUtils;
import org.dacapo.harness.util.LinuxSysfs;
import org.dacapo.proc.ProcStatus;

import java.io.BufferedReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public final class LinuxProcStatus implements ProcStatus {
    private final long pid;
    private final Path path;

    public LinuxProcStatus(long pid) {
        this(pid, Paths.get("/proc", Long.toString(pid), "status"));
    }

    private LinuxProcStatus(long pid, Path path) {
        this.pid = pid;
        this.path = Objects.requireNonNull(path, "sysfsPath must not be null");
    }

    /**
     * Returns a {@code LinuxProcStatus} instance for the current process.
     *
     * @throws UnsupportedOperationException if the PID cannot be determined in the current environment
     */
    public static LinuxProcStatus self() {
        // Avoiding the Java Process API to remain compatible with Java 8
        long pid;
        try {
            pid = getPidFromRuntimeMXBean();
        } catch (Exception e) {
            System.err.printf("Error when reading self PID from RuntimeMXBean: %s%n", e.getMessage());
            e.printStackTrace();
            System.err.println("Falling back to reading the PID from '/proc/self'...");
            pid = getPidFromProcFileSystemFallback(e);
        }

        return new LinuxProcStatus(pid);
    }

    private static long getPidFromRuntimeMXBean() {
        final String name = ManagementFactory.getRuntimeMXBean().getName();
        return AsciiUtils.parseFirstUnsignedLong(name);
    }

    /**
     * Falls back into reading the PID from the Linux proc filesystem as a result of the specified exception.
     *
     * @param rootException the exception that led to the fallback
     * @throws UnsupportedOperationException if the PID cannot be determined in the current environment
     */
    private static long getPidFromProcFileSystemFallback(Exception rootException) {
        try {
            final Path target = Files.readSymbolicLink(Paths.get("/proc/self"));
            final String pidString = target.getFileName().toString();
            return Long.parseLong(pidString);
        } catch (Exception e) {
            e.addSuppressed(rootException);
            final String msg = "The PID of the current process cannot be determined in the current environment";
            throw new UnsupportedOperationException(msg, e);
        }
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public long peakRssKb() {
        return readLong(Field.PEAK_RSS, -1L);
    }

    @SuppressWarnings("SameParameterValue")
    private long readLong(LinuxProcStatus.Field field, long defaultOnError) {
        final String fieldLine = readFieldLine(field);
        return fieldLine.isEmpty() ? defaultOnError : AsciiUtils.parseFirstUnsignedLong(fieldLine);
    }

    // Returns the first proc status line that contains the specified field, or the empty string if no such line exists.
    private String readFieldLine(LinuxProcStatus.Field field) {
        final String fieldName = field.name;
        final String fieldPrefix = fieldName + ':';
        try (BufferedReader in = Files.newBufferedReader(path, LinuxSysfs.CHARSET)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith(fieldPrefix)) {
                    return line;
                }
            }

            System.err.printf("%s (%s) not found in %s%n", field.friendlyName, fieldName, path);
            return "";
        } catch (Exception e) {
            System.err.printf("Error when reading %s from %s: %s%n", field.friendlyName, path, e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    public enum Field {
        PEAK_RSS("VmHWM", "Peak RSS");

        private final String name;
        private final String friendlyName;

        Field(String name, String friendlyName) {
            this.name = name;
            this.friendlyName = friendlyName;
        }
    }
}
