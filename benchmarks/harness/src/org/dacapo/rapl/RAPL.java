package org.dacapo.rapl;

import org.dacapo.rapl.linux.LinuxRAPL;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import static org.dacapo.harness.util.SystemUtils.IS_OS_LINUX;
import static org.dacapo.harness.util.SystemUtils.OS_NAME;

/**
 * An interface for reading energy values from Intel RAPL.
 */
public interface RAPL {

    /**
     * Returns the top-level domains of the RAPL hierarchy (packages and, if available, psys), keyed by name.
     */
    Map<String, Domain> getTopLevelDomains();

    /**
     * Returns a snapshot of a top-level domain and all of its subdomains.
     *
     * @param topLevelDomainName the name of the top-level domain
     * @throws IllegalArgumentException if the specified domain does not exist in the top-level domains map
     */
    @SuppressWarnings("unused")
    DomainSnapshot topLevelSnapshot(String topLevelDomainName);

    /**
     * Returns a {@code RAPL} implementation for the current operating system.
     *
     * @throws UnsupportedOperationException if there is no {@code RAPL} implementation for the current operating system
     */
    static RAPL getInstance() {
        if (IS_OS_LINUX) {
            return LinuxRAPL.getInstance();
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + OS_NAME);
        }
    }

    /**
     * A RAPL domain type.
     */
    enum DomainType {
        PKG, PP0, PP1, DRAM, PSYS, UNKNOWN;

        /**
         * Parse a domain name to a domain type in a case-insensitive manner.
         *
         * @param name the domain name
         * @return the matching {@link DomainType}, or {@link DomainType#UNKNOWN} if the name is unmatched
         */
        public static DomainType fromName(String name) {
            Objects.requireNonNull(name, "name must not be null");

            if (name.equalsIgnoreCase("core")) {
                return PP0;
            } else if (name.equalsIgnoreCase("uncore")) {
                return PP1;
            } else if (name.equalsIgnoreCase("dram")) {
                return DRAM;
            } else if (name.equalsIgnoreCase("psys")) {
                return PSYS;
            } else if (name.regionMatches(true, 0, "package", 0, "package".length())) {
                return PKG;
            } else {
                return UNKNOWN;
            }
        }
    }

    /**
     * A specific RAPL domain.
     */
    interface Domain {

        /**
         * Returns the name of this domain, e.g., package-0.
         */
        String name();

        /**
         * Returns the domain type.
         */
        DomainType type();

        /**
         * Returns an unmodifiable map with the subdomains of this domain.
         */
        @SuppressWarnings("unused")
        Map<String, Domain> subdomains();

        /**
         * Returns the value of the energy counter from this domain in Joules,
         * or -1 if the energy counter could not be read.
         */
        double energyJ();

        /**
         * Returns the maximum energy value supported by this domain in Joules.
         */
        double maxEnergyRangeJ();

        /**
         * Returns a {@link DomainSnapshot snapshot} of this domain.
         */
        DomainSnapshot snapshot();
    }

    /**
     * An energy snapshot of a RAPL domain and respective subdomains (if any).
     */
    interface DomainSnapshot {

        /**
         * Returns the domain of this snapshot.
         */
        Domain domain();

        /**
         * Returns the time at which this snapshot was taken.
         */
        Instant timestamp();

        /**
         * Returns the top-level energy value, in Joules, at the time of the snapshot.
         */
        double energyJ();

        /**
         * Returns subdomain snapshots, keyed by domain name.
         */
        Map<String, DomainSnapshot> subdomains();

        /**
         * Returns the energy value of the specified subdomain if it exists in {@link #subdomains()},
         * otherwise returns {@code defaultValue}.
         *
         * @param subdomainName the name of the subdomain
         * @param defaultValue  the default value
         */
        @SuppressWarnings("unused")
        default double subdomainEnergyJ(String subdomainName, double defaultValue) {
            final DomainSnapshot subdomainSnapshot = subdomains().get(subdomainName);
            return subdomainSnapshot == null ? defaultValue : subdomainSnapshot.energyJ();
        }
    }

    /**
     * A delta between two snapshots of the same domain.
     * <p>
     * Constructors must throw an {@link IllegalArgumentException} in the following cases:
     * <ol>
     *     <li>The timestamp of the first snapshot is after the timestamp of the second.</li>
     *     <li>The root domain name of the two snapshots is different.</li>
     * </ol>
     */
    interface DomainDelta {

        /**
         * Returns the first snapshot from this delta.
         */
        DomainSnapshot before();


        /**
         * Returns the second snapshots from this delta.
         */
        DomainSnapshot after();

        /**
         * Returns the energy difference between the first and second snapshot.
         */
        double energyJ();

        /**
         * Returns the subdomain deltas, keyed by domain name.
         */
        Map<String, DomainDelta> subdomains();

        /**
         * Returns the energy delta of the specified subdomain if it exists in {@link #subdomains()},
         * otherwise returns {@code defaultValue}.
         *
         * @param subdomainName the name of the subdomain
         * @param defaultValue  the default value
         */
        default double subdomainEnergyJ(String subdomainName, double defaultValue) {
            final DomainDelta subdomainDelta = subdomains().get(subdomainName);
            return subdomainDelta == null ? defaultValue : subdomainDelta.energyJ();
        }
    }
}
