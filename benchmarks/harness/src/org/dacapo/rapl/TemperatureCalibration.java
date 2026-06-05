package org.dacapo.rapl;

import org.dacapo.coretemp.CoreTemp;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * CPU temperature calibration class used to prevent temperature-dependent performance variation.
 */
public final class TemperatureCalibration {
    public static final String BASELINE_FILE_PATH = "/tmp/cpu_temperature_baseline.txt";

    private static final String INTERRUPTED_MSG = "Interrupted";
    private static final TemperatureCalibration INSTANCE = new TemperatureCalibration(CoreTemp.getInstance());

    private final CoreTemp coreTemp;

    private TemperatureCalibration(CoreTemp coreTemp) {
        this.coreTemp = coreTemp;
    }

    public static TemperatureCalibration getInstance() {
        return INSTANCE;
    }

    /**
     * Determines the baseline temperature for all CPU package by idling for {@code seconds} and reading the maximum
     * temperature of all packages.
     * <p>
     * When successful, this method stores the baseline temperature in {@link #BASELINE_FILE_PATH} for future
     * invocations of {@link #getBaselineTemperature}).
     *
     * @param seconds idle time before sampling the baseline temperature
     * @return the calibrated baseline temperature, or {@code -1} on error
     */
    public float calibrate(long seconds) {
        System.out.printf("Sleeping %d seconds to calibrate the CPU temperature...%n", seconds);
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            System.out.println(INTERRUPTED_MSG);
            Thread.currentThread().interrupt();
            return -1.0f;
        }

        final float maxPkgTemperature = getMaxPackageTemperature();
        if (maxPkgTemperature == -1.0f) {
            System.err.println("Failed to read CPU package temperatures");
            return -1.0f;
        }

        try (PrintWriter out = new PrintWriter(new FileWriter(BASELINE_FILE_PATH))) {
            out.printf("%.1f%n", maxPkgTemperature);
        } catch (IOException e) {
            System.err.printf("Error when writing baseline temperature file: %s%n", e.getMessage());
            return -1.0f;
        }

        System.out.printf("Calibrated package temperature: %.1f°C%n", maxPkgTemperature);
        return maxPkgTemperature;
    }

    /**
     * Returns the baseline temperature from a {@link #calibrate(long) previous calibration} when available;
     * otherwise, performs a new calibration and returns the result.
     *
     * @return the baseline temperature, or {@code -1} if unavailable
     */
    public float getBaselineTemperature(long temperatureCalibrationSeconds) {
        final Path file = Paths.get(BASELINE_FILE_PATH);
        if (Files.exists(file)) {
            try (BufferedReader in = Files.newBufferedReader(file)) {
                return Float.parseFloat(in.readLine());
            } catch (IOException | NumberFormatException e) {
                System.err.printf("Error when reading temperature file: %s. Retrying calibration...%n", e.getMessage());
                return calibrate(temperatureCalibrationSeconds);
            }
        } else {
            System.out.printf("File '%s' not found. Running CPU temperature calibration...%n", BASELINE_FILE_PATH);
            return calibrate(temperatureCalibrationSeconds);
        }
    }

    /**
     * Waits until the package temperature drops below {@code threshold}, sampling every second.
     *
     * @param threshold the baseline temperature
     * @throws IllegalArgumentException if the threshold is non-positive
     */
    public void waitForCooldown(float threshold) {
        if (threshold <= 0.0f) {
            throw new IllegalArgumentException("threshold must be positive");
        }

        float maxPkgTemperature = getMaxPackageTemperature();
        while (maxPkgTemperature > threshold) {
            System.out.printf("Sleeping 1 second... Current temperature: %.1f°C | ", maxPkgTemperature);
            System.out.printf("Reference temperature: %.1f°C | ", threshold);
            System.out.println("Waiting for temperature to drop below reference...");
            try {
                TimeUnit.SECONDS.sleep(1L);
            } catch (InterruptedException e) {
                System.out.println(INTERRUPTED_MSG);
                Thread.currentThread().interrupt();
                return;
            }
            maxPkgTemperature = getMaxPackageTemperature();
        }
    }

    private float getMaxPackageTemperature() {
        final Set<Integer> availablePkgs = coreTemp.getAvailablePackages();
        float maxTemperature = -1.0f;

        for (int pkgNumber : availablePkgs) {
            final float pkgTemperature = coreTemp.getPackageTemperatureCelsius(pkgNumber);
            maxTemperature = Math.max(maxTemperature, pkgTemperature);
        }

        return maxTemperature;
    }
}
