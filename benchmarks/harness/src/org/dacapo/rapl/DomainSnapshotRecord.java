package org.dacapo.rapl;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable implementation of {@link RAPL.DomainSnapshot}.
 * <p>
 * Written as a class to work in Java versions that do not support records.
 */
public final class DomainSnapshotRecord implements RAPL.DomainSnapshot {
    private final RAPL.Domain domain;
    private final Instant timestamp;
    private final double energyJ;
    private final Map<String, DomainSnapshotRecord> subdomains;

    /**
     * Constructs a {@code DomainSnapshotRecord} with the specified arguments.
     *
     * @param domain     the domain of this snapshot
     * @param timestamp  the time at which the snapshot was taken
     * @param energyJ    the current energy value of the domain in Joules
     * @param subdomains the snapshots of the subdomains, keyed by name
     */
    public DomainSnapshotRecord(RAPL.Domain domain, Instant timestamp,
                                double energyJ, Map<String, DomainSnapshotRecord> subdomains) {
        this.domain = Objects.requireNonNull(domain, "domain must not be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        this.energyJ = energyJ;
        this.subdomains = new HashMap<>(subdomains);
    }

    @Override
    public RAPL.Domain domain() {
        return domain;
    }

    @Override
    public Instant timestamp() {
        return timestamp;
    }

    @Override
    public double energyJ() {
        return energyJ;
    }

    @Override
    public Map<String, RAPL.DomainSnapshot> subdomains() {
        return Collections.unmodifiableMap(subdomains);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DomainSnapshotRecord)) {
            return false;
        }

        DomainSnapshotRecord that = (DomainSnapshotRecord) o;
        return domain.name().equals(that.domain.name()) &&
                timestamp.equals(that.timestamp) &&
                Double.compare(energyJ, that.energyJ) == 0 &&
                subdomains.equals(that.subdomains);
    }

    @Override
    public int hashCode() {
        int result = domain.name().hashCode();
        result = 31 * result + timestamp.hashCode();
        result = 31 * result + Double.hashCode(energyJ);
        result = 31 * result + subdomains.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "DomainSnapshotRecord[" +
                "domain=" + domain.name() +
                ", timestamp=" + timestamp +
                ", energyJ=" + energyJ +
                ", subdomains=" + subdomains +
                ']';
    }
}
