package org.dacapo.rapl;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.emptyMap;

public final class DomainDeltaRecord implements RAPL.DomainDelta {
    private final RAPL.DomainSnapshot before;
    private final RAPL.DomainSnapshot after;
    private final double energyJ;
    private final Map<String, DomainDeltaRecord> subdomains;

    public DomainDeltaRecord(RAPL.DomainSnapshot before, RAPL.DomainSnapshot after) {
        this.before = before;
        this.after = after;
        this.energyJ = getRootDomainDelta(before, after);
        this.subdomains = getSubdomainDeltas(before, after);
    }

    private static double getRootDomainDelta(RAPL.DomainSnapshot before, RAPL.DomainSnapshot after) {
        if (before == null || after == null) {
            return -1.0;
        }

        final RAPL.Domain beforeDomain = before.domain();
        final RAPL.Domain afterDomain = after.domain();
        if (!beforeDomain.name().equals(afterDomain.name())) {
            throw new IllegalArgumentException("Both snapshots must refer to the same domain");
        }

        if (before.timestamp().isAfter(after.timestamp())) {
            throw new IllegalArgumentException("The first snapshot must have been taken before the second");
        }

        final double beforeEnergyJ = before.energyJ();
        final double afterEnergyJ = after.energyJ();
        if (beforeEnergyJ == -1.0 || afterEnergyJ == -1.0) {
            return -1.0;
        } else if (beforeEnergyJ > afterEnergyJ) { // overflow case
            return (beforeDomain.maxEnergyRangeJ() - beforeEnergyJ) + afterEnergyJ;
        } else {
            return afterEnergyJ - beforeEnergyJ;
        }
    }

    private static Map<String, DomainDeltaRecord> getSubdomainDeltas(RAPL.DomainSnapshot before, RAPL.DomainSnapshot after) {
        Map<String, RAPL.DomainSnapshot> beforeSubdomainSnapshots = before == null ? emptyMap() : before.subdomains();
        Map<String, RAPL.DomainSnapshot> afterSubdomainSnapshots = after == null ? emptyMap() : after.subdomains();

        Set<String> subdomainNames = new HashSet<>();
        subdomainNames.addAll(beforeSubdomainSnapshots.keySet());
        subdomainNames.addAll(afterSubdomainSnapshots.keySet());

        Map<String, DomainDeltaRecord> deltaMap = new HashMap<>();
        for (String name : subdomainNames) {
            RAPL.DomainSnapshot beforeSnapshot = beforeSubdomainSnapshots.get(name);
            RAPL.DomainSnapshot afterSnapshot = afterSubdomainSnapshots.get(name);
            deltaMap.put(name, new DomainDeltaRecord(beforeSnapshot, afterSnapshot));
        }

        return deltaMap;
    }

    @Override
    public RAPL.DomainSnapshot before() {
        return before;
    }

    @Override
    public RAPL.DomainSnapshot after() {
        return after;
    }

    @Override
    public double energyJ() {
        return energyJ;
    }

    @Override
    public Map<String, RAPL.DomainDelta> subdomains() {
        return Collections.unmodifiableMap(subdomains);
    }
}
