package org.dacapo.harness.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.stream.Stream;

public final class SystemUtils {
    // Operating System
    public static final String OS_NAME = System.getProperty("os.name", "");
    public static final boolean IS_OS_WINDOWS = osNameMatches("windows");
    public static final boolean IS_OS_MAC = osNameMatches("mac");
    public static final boolean IS_OS_LINUX = osNameMatches("linux");

    // CPU Architecture
    public static final boolean IS_ARCH_INTEL = isArchIntel();
    public static final String GENUINE_INTEL_LABEL = "GenuineIntel";

    private SystemUtils() {
    }

    public static boolean osNameMatches(String osNamePrefix) {
        Objects.requireNonNull(osNamePrefix, "osNamePrefix must not be null");
        return OS_NAME.regionMatches(true, 0, osNamePrefix, 0, osNamePrefix.length());
    }

    private static boolean isArchIntel() {
        try {
            if (IS_OS_WINDOWS) {
                String cmd = "@((Get-CimInstance Win32_Processor).Manufacturer) -contains 'GenuineIntel'";
                Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", cmd).start();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = in.readLine();
                    return line != null && line.trim().equalsIgnoreCase("true");
                }
            } else if (IS_OS_MAC) {
                Process process = new ProcessBuilder("sysctl", "-n", "machdep.cpu.vendor").start();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = in.readLine();
                    return line != null && line.contains(GENUINE_INTEL_LABEL);
                }
            } else if (IS_OS_LINUX) {
                Path cpuInfoPath = Paths.get("/proc/cpuinfo");
                try (Stream<String> cpuInfoLines = Files.lines(cpuInfoPath)) {
                    return cpuInfoLines.anyMatch(line -> line.contains(GENUINE_INTEL_LABEL));
                }
            } else {
                System.err.printf("Unsupported OS for isArchIntel() check: %s%n", OS_NAME);
            }
        } catch (Exception e) {
            System.err.printf("isArchIntel() error: %s%n", e.getMessage());
            e.printStackTrace();
        }
        // Only reached when the CPU architecture could not be determined
        return false;
    }
}
