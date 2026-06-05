package org.dacapo.coretemp;

import org.dacapo.coretemp.linux.LinuxIntelCoreTemp;

import java.util.Set;

import static org.dacapo.harness.util.SystemUtils.IS_ARCH_INTEL;
import static org.dacapo.harness.util.SystemUtils.IS_OS_LINUX;
import static org.dacapo.harness.util.SystemUtils.OS_NAME;

@SuppressWarnings("unused")
public interface CoreTemp {

    /**
     * Returns the numbers of the CPU packages with at least one available sensor.
     */
    Set<Integer> getAvailablePackages();

    /**
     * Returns the numbers of the CPU cores of the specified package with at least one available temperature sensor.
     *
     * @param pkgNumber the CPU package number
     * @throws IllegalArgumentException if the specified package could not be found
     */
    Set<Integer> getAvailableCores(int pkgNumber);

    /**
     * Returns the temperature of the specified package in degrees Celsius.
     * <p>
     * If the processor does not have a package sensor, this method must return the current maximum temperature of
     * all available cores; otherwise, it shall return the current temperature reading from the package sensor.
     *
     * @param pkgNumber the package number
     * @throws IllegalArgumentException if the specified package could not be found
     */
    float getPackageTemperatureCelsius(int pkgNumber);

    /**
     * Returns the temperature of the specified core in degrees Celsius.
     *
     * @param pkgNumber  the package number
     * @param coreNumber the core number
     * @throws IllegalArgumentException if the specified core could not be found
     */
    float getCoreTemperatureCelsius(int pkgNumber, int coreNumber);

    /**
     * Returns a {@code CoreTemp} implementation for the current operating system and CPU architecture.
     * <p>
     * Note that this method is designed for machines with a single CPU architecture. When invoked in machines with
     * multiple CPU architectures, it may return an implementation for any of those architectures.
     *
     * @throws UnsupportedOperationException if there is no {@code CoreTemp} implementation for the current
     *                                       operating system and CPU architecture
     */
    static CoreTemp getInstance() {
        if (IS_OS_LINUX && IS_ARCH_INTEL) {
            return LinuxIntelCoreTemp.getInstance();
        } else {
            throw new UnsupportedOperationException("Unsupported processor or operating system");
        }

    }
}
