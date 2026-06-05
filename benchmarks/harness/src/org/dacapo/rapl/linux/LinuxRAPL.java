package org.dacapo.rapl.linux;

import org.dacapo.harness.util.ExceptionUtils;
import org.dacapo.harness.util.LinuxSysfs;
import org.dacapo.rapl.DomainSnapshotRecord;
import org.dacapo.rapl.RAPL;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

// Reference: https://docs.kernel.org/power/powercap/powercap.html
public final class LinuxRAPL implements RAPL {
    private static final String SYSFS_BASE_DIR = "/sys/class/powercap/intel-rapl/";

    private final Map<String, LinuxRAPL.Domain> topLevelDomains;

    private LinuxRAPL(Map<String, LinuxRAPL.Domain> topLevelDomains) {
        this.topLevelDomains = new HashMap<>(topLevelDomains);
    }

    /**
     * Returns the {@code LinuxRAPL} instance.
     */
    public static LinuxRAPL getInstance() {
        return LinuxRAPL.SingletonHolder.INSTANCE;
    }

    @Override
    public Map<String, RAPL.Domain> getTopLevelDomains() {
        return Collections.unmodifiableMap(topLevelDomains);
    }

    @Override
    public DomainSnapshotRecord topLevelSnapshot(String topLevelDomainName) {
        final LinuxRAPL.Domain domain = topLevelDomains.get(topLevelDomainName);
        if (domain == null) {
            throw new IllegalArgumentException("domain '" + topLevelDomainName + " not found");
        } else {
            return domain.snapshot();
        }
    }

    // N.B.: Lifecycle methods (open, close, etc.) must only be used in the static init of the singleton holder.
    private static LinuxRAPL open() throws IOException {
        final Path sysfsBasePath = Paths.get(LinuxRAPL.SYSFS_BASE_DIR);
        final Map<String, LinuxRAPL.Domain> topLevelDomains = LinuxRAPL.Domain.openSubdomains(sysfsBasePath);
        if (topLevelDomains.isEmpty()) {
            System.err.printf("No RAPL domains were found at '%s'%n", sysfsBasePath);
            System.err.println("RAPL energy measurements will be unavailable");
        }
        return new LinuxRAPL(topLevelDomains);
    }

    private static LinuxRAPL tryOpen() {
        try {
            return open();
        } catch (IOException e) {
            System.err.printf("Error when opening RAPL sysfs (%s): %s%n", SYSFS_BASE_DIR, e.getMessage());
            e.printStackTrace();
            return new LinuxRAPL(Collections.emptyMap());
        }
    }

    private void close() throws Exception {
        Exception rootException = null;
        for (LinuxRAPL.Domain domain : topLevelDomains.values()) {
            rootException = ExceptionUtils.runSafely(domain::close, rootException);
        }

        if (rootException != null) {
            throw rootException;
        }
    }

    private void tryClose() {
        try {
            close();
        } catch (Exception e) {
            System.err.printf("Error when closing RAPL sysfs (%s): %s%n", SYSFS_BASE_DIR, e.getMessage());
            e.printStackTrace();
        }
    }

    private static final class Domain implements RAPL.Domain {
        private static final String POWER_ZONE_GLOB = "intel-rapl:[0-9]*";

        private final String name;
        private final DomainType type;
        private final Map<String, LinuxRAPL.Domain> subdomains;
        private final Path sysfsPath;
        private final long maxEnergyRangeUj;
        private final RandomAccessFile energyUjFile;
        private final byte[] energyUjBuffer;
        private final ReentrantLock lock;

        private Domain(String name, DomainType type, Map<String, LinuxRAPL.Domain> subdomains,
                       Path sysfsPath, long maxEnergyRangeUj, RandomAccessFile energyUjFile) {
            this.name = name;
            this.type = type;
            this.subdomains = new HashMap<>(subdomains);
            this.sysfsPath = sysfsPath;
            this.maxEnergyRangeUj = maxEnergyRangeUj;
            this.energyUjFile = energyUjFile;
            this.energyUjBuffer = newEnergyUjBuffer(maxEnergyRangeUj);
            this.lock = new ReentrantLock();
        }

        private static byte[] newEnergyUjBuffer(long maxEnergyRangeUj) {
            // length of max string + 1 byte for '\n'
            final int length = Long.toString(maxEnergyRangeUj).length() + 1;
            return new byte[length];
        }

        private static LinuxRAPL.Domain open(Path sysfsPath) throws IOException {
            final String name = LinuxSysfs.readLine(sysfsPath.resolve("name"));
            final DomainType type = DomainType.fromName(name);
            final long maxEnergyRangeUj = LinuxSysfs.readLong(sysfsPath.resolve("max_energy_range_uj"));
            final Path energyUjFilePath = sysfsPath.resolve("energy_uj");
            final RandomAccessFile energyUjFile = new RandomAccessFile(energyUjFilePath.toFile(), "r");
            Map<String, LinuxRAPL.Domain> subdomains;

            try {
                subdomains = LinuxRAPL.Domain.openSubdomains(sysfsPath);
            } catch (IOException e) {
                subdomains = Collections.emptyMap();
                System.err.printf("Error when opening subdomains of '%s': %s%n", sysfsPath, e.getMessage());
                e.printStackTrace();
                System.err.println("The energy usage of the associated subdomains will be unavailable");
            }

            return new Domain(name, type, subdomains, sysfsPath, maxEnergyRangeUj, energyUjFile);
        }

        private static Map<String, LinuxRAPL.Domain> openSubdomains(Path basePath) throws IOException {
            final Map<String, LinuxRAPL.Domain> subdomains = new HashMap<>();

            try (DirectoryStream<Path> subzonesDirStream = Files.newDirectoryStream(basePath, POWER_ZONE_GLOB)) {
                for (Path subzonePath : subzonesDirStream) {
                    tryOpen(subzonePath, subdomains);
                }
            }

            return Collections.unmodifiableMap(subdomains);
        }

        private static void tryOpen(Path sysfsPath, Map<String, LinuxRAPL.Domain> domains) {
            try {
                final LinuxRAPL.Domain domain = LinuxRAPL.Domain.open(sysfsPath);
                domains.put(domain.name, domain);
            } catch (Exception e) {
                System.err.printf("Error when parsing RAPL domain at '%s': %s%n", sysfsPath, e.getMessage());
                e.printStackTrace();
                System.err.println("The energy usage of this domain will be unavailable");
            }
        }

        @Override
        public String name() {
            return name;
        }

        public DomainType type() {
            return type;
        }

        @Override
        public Map<String, RAPL.Domain> subdomains() {
            return Collections.unmodifiableMap(subdomains);
        }

        @Override
        public double energyJ() {
            final long energyUj = energyUj();
            return energyUj == -1L ? -1.0 : energyUj / 1_000_000.0;
        }

        @Override
        public double maxEnergyRangeJ() {
            return maxEnergyRangeUj / 1_000_000.0;
        }

        @Override
        public DomainSnapshotRecord snapshot() {
            final double energyJ = energyJ();
            final Map<String, DomainSnapshotRecord> subdomainSnapshots = new HashMap<>();

            for (Domain s : subdomains.values()) {
                final DomainSnapshotRecord subdomainSnapshot = s.snapshot();
                subdomainSnapshots.put(s.name, subdomainSnapshot);
            }

            return new DomainSnapshotRecord(this, Instant.now(), energyJ, subdomainSnapshots);
        }

        /**
         * Returns the value of the energy counter from this domain in micro-joules,
         * or -1 if the energy counter could not be read.
         */
        public long energyUj() {
            lock.lock();
            try {
                energyUjFile.seek(0L);
                final int byteCount = energyUjFile.read(energyUjBuffer);
                if (byteCount == -1) {
                    return -1L;
                }

                final String energyUjString = new String(energyUjBuffer, 0, byteCount, LinuxSysfs.CHARSET).trim();
                return Long.parseLong(energyUjString);
            } catch (Exception e) {
                System.err.printf("Error when reading energy from %s: %s%n", sysfsPath, e.getMessage());
                e.printStackTrace();
                return -1L;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns the range of the energy counter from this domain in microjoule.
         */
        @SuppressWarnings("unused")
        public long maxEnergyRangeUj() {
            return maxEnergyRangeUj;
        }

        private void close() throws Exception {
            lock.lock();
            try {
                Exception rootException = null;
                rootException = ExceptionUtils.runSafely(energyUjFile::close, rootException);
                for (LinuxRAPL.Domain domain : subdomains.values()) {
                    rootException = ExceptionUtils.runSafely(domain::close, rootException);
                }

                if (rootException != null) {
                    throw rootException;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class SingletonHolder {
        private static final LinuxRAPL INSTANCE;

        static {
            INSTANCE = LinuxRAPL.tryOpen();
            Runtime.getRuntime().addShutdownHook(new Thread(INSTANCE::tryClose, "RAPL-Cleanup-Thread"));
        }
    }
}
