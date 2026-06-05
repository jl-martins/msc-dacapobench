package org.dacapo.coretemp.linux;

import org.dacapo.coretemp.CoreTemp;
import org.dacapo.harness.util.AsciiUtils;
import org.dacapo.harness.util.ExceptionUtils;
import org.dacapo.harness.util.LinuxSysfs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Linux-specific implementation of the {@code CoreTemp} interface that reads core temperatures from
 * <a href="https://docs.kernel.org/hwmon/coretemp.html">kernel driver coretemp</a>.
 */
public final class LinuxIntelCoreTemp implements CoreTemp {
    private static final String HWMON_DIR = "/sys/class/hwmon/";
    private static final String DRIVER_NAME = "coretemp";
    private static final String HWMON_DEVICE_GLOB = "hwmon[0-9]*";
    private static final String CORETEMP_LABELS_GLOB = "temp[0-9]*_label";
    private static final String PHYSICAL_PKG_ID_DIR_FMT = "/sys/devices/system/cpu/cpu%d/topology/physical_package_id";

    private final Map<Integer, LinuxIntelCoreTemp.Device> pkgNumberToDevice;

    private LinuxIntelCoreTemp(Map<Integer, LinuxIntelCoreTemp.Device> pkgNumberToDevice) {
        this.pkgNumberToDevice = pkgNumberToDevice;
    }

    public static LinuxIntelCoreTemp getInstance() {
        return LinuxIntelCoreTemp.SingletonHolder.INSTANCE;
    }

    private static LinuxIntelCoreTemp open() throws IOException {
        final Map<Integer, LinuxIntelCoreTemp.Device> pkgNumberToDevice = new HashMap<>();
        final Path hwmonPath = Paths.get(HWMON_DIR);
        try (DirectoryStream<Path> hwmonDeviceDirStream = Files.newDirectoryStream(hwmonPath, HWMON_DEVICE_GLOB)) {
            for (Path hwmonDevicePath : hwmonDeviceDirStream) {
                tryOpenDevice(hwmonDevicePath, pkgNumberToDevice);
            }
        }

        return new LinuxIntelCoreTemp(Collections.unmodifiableMap(pkgNumberToDevice));
    }

    private static LinuxIntelCoreTemp tryOpen() {
        try {
            return open();
        } catch (Exception e) {
            System.err.printf("Error when opening coretemp entries: %s%n", e.getMessage());
            e.printStackTrace();
            System.err.println("Intel CPU temperatures will be unavailable");
            return new LinuxIntelCoreTemp(Collections.emptyMap());
        }
    }

    private static void tryOpenDevice(Path hwmonDevicePath, Map<Integer, LinuxIntelCoreTemp.Device> pkgNumberToDevice) {
        final Path driverNamePath = hwmonDevicePath.resolve("name");
        final String driverName;
        try {
            driverName = LinuxSysfs.readLine(driverNamePath);
        } catch (NoSuchFileException | FileNotFoundException e) {
            System.err.printf("Hwmon device '%s' does not have a name. Skipping...%n", hwmonDevicePath);
            return;
        } catch (Exception e) {
            System.err.printf("Error when reading driver name at '%s': %s%n", driverNamePath, e.getMessage());
            e.printStackTrace();
            System.err.println("The temperature of the corresponding device will be unavailable");
            return;
        }

        if (driverName.equals(LinuxIntelCoreTemp.DRIVER_NAME)) {
            try {
                final LinuxIntelCoreTemp.Device device = LinuxIntelCoreTemp.Device.open(hwmonDevicePath);
                final int pkgNumber = device.getPackageNumber();
                final LinuxIntelCoreTemp.Device existingDevice = pkgNumberToDevice.putIfAbsent(pkgNumber, device);
                if (existingDevice != null) {
                    System.err.println("Skipped device with existing package number: " + pkgNumber);
                    device.close();
                }
            } catch (Exception e) {
                System.err.printf("Error when opening coretemp device at '%s': %s%n", hwmonDevicePath, e.getMessage());
                e.printStackTrace();
                System.err.println("The temperature of the corresponding CPU will be unavailable");
            }
        }
    }

    @Override
    public Set<Integer> getAvailablePackages() {
        return pkgNumberToDevice.keySet();
    }

    @Override
    public Set<Integer> getAvailableCores(int pkgNumber) {
        final Device device = getDeviceOrElseThrow(pkgNumber);
        return device.getAvailableCores();
    }

    @Override
    public float getPackageTemperatureCelsius(int pkgNumber) {
        return getDeviceOrElseThrow(pkgNumber).getPackageTemperatureCelsius();
    }

    @Override
    public float getCoreTemperatureCelsius(int pkgNumber, int coreNumber) {
        return getDeviceOrElseThrow(pkgNumber).getCoreTemperatureCelsius(coreNumber);
    }

    private Device getDeviceOrElseThrow(int pkgNumber) {
        final Device device = pkgNumberToDevice.get(pkgNumber);
        if (device != null) {
            return device;
        } else {
            String msg = "Package " + pkgNumber + " not found. Valid packages are: " + pkgNumberToDevice.keySet();
            throw new IllegalArgumentException(msg);
        }
    }

    private void close() throws Exception {
        Exception rootException = null;
        for (LinuxIntelCoreTemp.Device device : pkgNumberToDevice.values()) {
            rootException = ExceptionUtils.runSafely(device::close, rootException);
        }
        if (rootException != null) {
            throw rootException;
        }
    }

    private void tryClose() {
        try {
            close();
        } catch (Exception e) {
            System.err.printf("Error when closing coretemp devices: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }

    public static final class Device {
        private final int pkgNumber;
        private final Sensor pkgSensor;
        private final Map<Integer, Sensor> coreSensors;

        private Device(int pkgNumber, Sensor pkgSensor, Map<Integer, Sensor> coreSensors) {
            this.pkgNumber = pkgNumber;
            this.pkgSensor = pkgSensor;
            this.coreSensors = coreSensors;
        }

        private static Device.Builder builder() {
            return new Builder();
        }

        private static Device open(Path hwmonDevicePath) throws IOException {
            final Device.Builder builder = Device.builder();
            try (DirectoryStream<Path> labels = Files.newDirectoryStream(hwmonDevicePath, CORETEMP_LABELS_GLOB)) {
                for (Path l : labels) {
                    tryOpenSensor(l, builder);
                }
            }

            final Sensor pkgSensor = builder.pkgSensor;
            final Map<Integer, Sensor> coreSensors = builder.coreSensors;
            if (pkgSensor == null && coreSensors.isEmpty()) {
                throw new IOException("No sensors were found at '" + hwmonDevicePath + "'");
            }

            int pkgNumber = pkgSensor == null ? getPackageNumberFromCpuTopology(coreSensors) : pkgSensor.label.number;
            return builder.pkgNumber(pkgNumber).build();
        }

        private static int getPackageNumberFromCpuTopology(Map<Integer, Sensor> coreSensors) throws IOException {
            assert !coreSensors.isEmpty();
            final Sensor anyCoreSensor = coreSensors.values().iterator().next();
            final int coreNumber = anyCoreSensor.label.number;
            final String pkgNumberDir = String.format(PHYSICAL_PKG_ID_DIR_FMT, coreNumber);
            return LinuxSysfs.readInt(Paths.get(pkgNumberDir));
        }

        private static void tryOpenSensor(Path labelPath, Device.Builder builder) {
            try {
                final Sensor sensor = Sensor.open(labelPath);
                final Sensor.Label label = sensor.label;
                switch (label.scope) {
                    case PKG: // assumption: each processor has at most one package sensor
                        builder.pkgSensor(sensor);
                        break;
                    case CORE:
                        builder.putCoreSensor(label.number, sensor);
                        break;
                    default:
                        System.err.printf("Ignoring unknown sensor '%s' with index %d%n", label.rawValue, sensor.index);
                        return;
                }
            } catch (Exception e) {
                System.err.printf("Error when opening sensor with label at '%s': %s%n", labelPath, e.getMessage());
                e.printStackTrace();
                System.err.println("Temperature readings from this sensor will be unavailable");
            }
        }

        public int getPackageNumber() {
            return pkgNumber;
        }

        /**
         * Returns the numbers of the CPU cores of this device with a temperature sensor.
         */
        public Set<Integer> getAvailableCores() {
            return coreSensors.keySet();
        }

        /**
         * Returns the package sensor from this device, or {@code null} if the package sensor is unavailable.
         */
        @SuppressWarnings("unused")
        public Sensor getPackageSensor() {
            return pkgSensor;
        }

        /**
         * Returns the temperature sensor of the specified core, or {@code null} if the sensor is unavailable.
         *
         * @param coreNumber the core number
         */
        @SuppressWarnings("unused")
        public Sensor getCoreSensor(int coreNumber) {
            return coreSensors.get(coreNumber);
        }

        /**
         * Returns the CPU package temperature in degrees Celsius.
         * <p>
         * If the package sensor is unavailable, the current maximum core temperature is returned instead.
         *
         * @return the current package temperature, the maximum core temperature as a fallback,
         * or {@link Float#NaN} if an error occurs.
         */
        public float getPackageTemperatureCelsius() {
            if (pkgSensor == null) {
                System.err.println("Package temperature is not available. Falling back to max core temperature...");
                return getMaxCoreTemperatureCelsius();
            } else {
                return pkgSensor.readTemperatureCelsius();
            }
        }

        /**
         * Returns the current maximum core temperature, or {@link Float#NaN} on error.
         */
        public float getMaxCoreTemperatureCelsius() {
            float max = Float.NEGATIVE_INFINITY;

            for (Sensor sensor : coreSensors.values()) {
                max = Math.max(max, sensor.readTemperatureCelsius());
            }

            return max == Float.NEGATIVE_INFINITY ? Float.NaN : max;
        }

        /**
         * Returns the temperature of the specified core, or {@link Float#NaN} on error.
         */
        public float getCoreTemperatureCelsius(int coreNumber) {
            final Sensor sensor = coreSensors.get(coreNumber);
            if (sensor == null) {
                Set<Integer> validCores = coreSensors.keySet();
                String msg = String.format("Core %d not found. Valid cores are: %s", coreNumber, validCores);
                throw new IllegalArgumentException(msg);
            }

            return sensor.readTemperatureCelsius();
        }

        private void close() throws Exception {
            Exception rootException = null;
            for (Sensor sensor : coreSensors.values()) {
                rootException = ExceptionUtils.runSafely(sensor::close, rootException);
            }
            if (rootException != null) {
                throw rootException;
            }
        }

        private static class Builder {
            private final Map<Integer, LinuxIntelCoreTemp.Sensor> coreSensors;
            private int pkgNumber;
            private LinuxIntelCoreTemp.Sensor pkgSensor;

            private Builder() {
                this.coreSensors = new HashMap<>();
            }

            private Device.Builder pkgNumber(int pkgNumber) {
                this.pkgNumber = pkgNumber;
                return this;
            }

            @SuppressWarnings("UnusedReturnValue")
            private Device.Builder pkgSensor(LinuxIntelCoreTemp.Sensor pkgSensor) {
                this.pkgSensor = pkgSensor;
                return this;
            }

            @SuppressWarnings("UnusedReturnValue")
            private Device.Builder putCoreSensor(int coreNumber, Sensor sensor) {
                coreSensors.put(coreNumber, sensor);
                return this;
            }

            private Device build() {
                return new Device(pkgNumber, pkgSensor, Collections.unmodifiableMap(coreSensors));
            }
        }
    }

    public static final class Sensor {
        private static final int TEMPERATURE_BUFFER_LENGTH = 16;
        private static final String CORE_LABEL_PREFIX = "Core";
        private static final String[] PKG_LABEL_PREFIXES = {"Package id", "Physical id"};

        private final int index;
        private final Label label;
        private final Path temperatureFilePath;
        private final RandomAccessFile temperatureFile;
        private final byte[] temperatureBuffer;
        private final ReentrantLock lock;

        private Sensor(int index, Label label, Path temperatureFilePath,
                       RandomAccessFile temperatureFile, byte[] temperatureBuffer) {
            this.index = index;
            this.label = label;
            this.temperatureFilePath = temperatureFilePath;
            this.temperatureFile = temperatureFile;
            this.temperatureBuffer = temperatureBuffer;
            this.lock = new ReentrantLock();
        }

        private static Sensor open(Path labelPath) throws IOException {
            final int index = AsciiUtils.parseFirstUnsignedInt(labelPath.getFileName().toString());
            final Label label = Label.fromPath(labelPath);
            final Path hwmonPath = labelPath.getParent();
            final Path temperatureFilePath = hwmonPath.resolve("temp" + index + "_input");
            final RandomAccessFile temperatureFile = new RandomAccessFile(temperatureFilePath.toFile(), "r");
            return new Sensor(index, label, temperatureFilePath, temperatureFile, new byte[TEMPERATURE_BUFFER_LENGTH]);
        }

        /**
         * Returns the current temperature from this sensor in degrees Celsius, or {@link Float#NaN} on error.
         */
        public float readTemperatureCelsius() {
            lock.lock();
            try {
                temperatureFile.seek(0L);
                final int byteCount = temperatureFile.read(temperatureBuffer);
                if (byteCount == -1) {
                    return Float.NaN;
                }

                String temperatureString = new String(temperatureBuffer, 0, byteCount, LinuxSysfs.CHARSET).trim();
                return Integer.parseInt(temperatureString) / 1000.0f; // temperature files are in millidegrees Celsius
            } catch (Exception e) {
                System.err.printf("Error when reading temperature from '%s': %s%n", temperatureFilePath, e.getMessage());
                e.printStackTrace();
                return Float.NaN;
            } finally {
                lock.unlock();
            }
        }

        private void close() throws IOException {
            temperatureFile.close();
        }

        private enum Scope {
            CORE,
            PKG,
            UNKNOWN
        }

        private static final class Label {
            private final String rawValue;
            private final Scope scope;
            private final int number;

            private Label(String rawValue, Scope scope, int number) {
                this.rawValue = rawValue;
                this.scope = scope;
                this.number = number;
            }

            private static Label fromPath(Path labelPath) throws IOException {
                final String rawValue = LinuxSysfs.readLine(labelPath);
                final Scope scope;
                final int number;

                if (rawValue.startsWith(CORE_LABEL_PREFIX)) {
                    scope = Scope.CORE;
                    number = AsciiUtils.parseFirstUnsignedInt(rawValue);
                } else if (Arrays.stream(PKG_LABEL_PREFIXES).anyMatch(rawValue::startsWith)) {
                    scope = Scope.PKG;
                    number = AsciiUtils.parseFirstUnsignedInt(rawValue);
                } else {
                    scope = Scope.UNKNOWN;
                    number = -1;
                }

                return new Label(rawValue, scope, number);
            }
        }
    }

    private static final class SingletonHolder {
        private static final LinuxIntelCoreTemp INSTANCE;

        static {
            INSTANCE = LinuxIntelCoreTemp.tryOpen();
            Runtime.getRuntime().addShutdownHook(new Thread(INSTANCE::tryClose, "Coretemp-Cleanup-Thread"));
        }
    }
}
