package org.dacapo.rapl;

import org.dacapo.coretemp.CoreTemp;
import org.dacapo.harness.Callback;
import org.dacapo.harness.CommandLineArgs;
import org.dacapo.parser.Config;
import org.dacapo.proc.ProcStatus;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.util.Objects.requireNonNull;

@SuppressWarnings("unused") // used by DaCapo when the callback is specified in the command line
// Limitation: For now, we only support energy measurements within package-0
public class RAPLCallback extends Callback {
    private static final String PKG_0_DOMAIN_NAME = "package-0";

    private static final String CSV_FILE = "measurements.csv";
    private static final String CSV_HEADER = "Language,Program,Powercap,Package,Core,Uncore,DRAM,Time,Temperature,Memory";
    private static final String CSV_RECORD_FMT = "Java,%s,-1,%.18f,%.18f,%.18f,%.18f,%d,%.1f,%d%n";

    private static final long TEMPERATURE_CALIBRATION_SECONDS = 30L;

    private final CoreTemp coretemp;
    private final TemperatureCalibration temperatureCalibration;
    private final ProcStatus procStatus;

    // Energy
    private final RAPL.Domain pkg0Domain;
    private RAPL.DomainSnapshot pkg0EnergyBefore;
    private RAPL.DomainSnapshot pkg0EnergyAfter;

    // Temperature
    private float baselineTemperature;
    private float pkg0TemperatureAfter;

    private long peakRssKbAfter;

    @SuppressWarnings("unused") // used by the DaCapo to instantiate the callback
    public RAPLCallback(CommandLineArgs args) {
        this(args, RAPL.getInstance(), CoreTemp.getInstance(), TemperatureCalibration.getInstance(), ProcStatus.self());
    }

    private RAPLCallback(CommandLineArgs args, RAPL rapl, CoreTemp coretemp,
                         TemperatureCalibration temperatureCalibration, ProcStatus procStatus) {
        super(args);
        this.coretemp = requireNonNull(coretemp, "coretemp must not be null");
        this.temperatureCalibration = requireNonNull(temperatureCalibration, "temperatureCalibration must not be null");
        this.procStatus = requireNonNull(procStatus, "procStatus must not be null");
        this.pkg0Domain = rapl.getTopLevelDomains().get(PKG_0_DOMAIN_NAME);
        this.pkg0EnergyBefore = null;
        this.pkg0EnergyAfter = null;
        this.pkg0TemperatureAfter = -1.0f;
        this.peakRssKbAfter = -1L;
    }

    @Override
    public void init(Config config) {
        super.init(config);
        baselineTemperature = temperatureCalibration.getBaselineTemperature(TEMPERATURE_CALIBRATION_SECONDS);
        if (baselineTemperature == -1.0f) {
            throw new IllegalStateException("Temperature calibration error");
        }
    }

    @Override
    protected void start(String benchmark, boolean warmup) {
        super.start(benchmark, warmup);
        if (warmup) { // measure only "warm" iterations
            return;
        }

        if (pkg0Domain == null) {
            System.err.printf("No '%s' domain was found.%n", PKG_0_DOMAIN_NAME);
            System.err.println("The current version of RAPLCallback only supports measurements within this domain");
            return;
        }

        temperatureCalibration.waitForCooldown(baselineTemperature);
        pkg0EnergyBefore = pkg0Domain.snapshot();
        System.out.printf("RAPLCallback: started measuring '%s' benchmark%n", benchmark);
    }

    @Override
    public void stop(long duration, boolean warmup) {
        super.stop(duration, warmup);
        if (warmup) {
            return;
        }

        if (pkg0Domain == null) {
            // Avoid repeating the error messages from the start() method
            return;
        }

        pkg0EnergyAfter = pkg0Domain.snapshot();
        pkg0TemperatureAfter = coretemp.getPackageTemperatureCelsius(0);
        peakRssKbAfter = procStatus.peakRssKb();
    }

    @Override
    protected void complete(String benchmark, boolean valid, boolean warmup) {
        super.complete(benchmark, valid, warmup);
        if (warmup) {
            return;
        }

        if (!valid) {
            System.err.printf("RAPLCallback: '%s' benchmark failed. Skipping measurement...%n", benchmark);
            return;
        }

        try {
            writeCsvRow(benchmark);
            System.out.printf("RAPLCallback: finished measuring '%s' benchmark%n", benchmark);
        } catch (IOException e) {
            System.err.printf("RAPLCallback: failed to write %s of '%s' benchmark: %s%n", CSV_FILE, benchmark, e.getMessage());
            e.printStackTrace();
        }
    }

    private void writeCsvRow(String benchmark) throws IOException {
        final Path csv = Paths.get(CSV_FILE);
        final boolean needsHeader = !Files.exists(csv) || Files.size(csv) == 0;

        try (FileWriter fw = new FileWriter(CSV_FILE, true);
             PrintWriter pw = new PrintWriter(fw)) {
            if (needsHeader) {
                pw.println(CSV_HEADER);
            }

            final RAPL.DomainDelta delta = new DomainDeltaRecord(pkg0EnergyBefore, pkg0EnergyAfter);
            double pkg = delta.energyJ();
            double core = delta.subdomainEnergyJ("core", -1.0);
            double uncore = delta.subdomainEnergyJ("uncore", -1.0);
            double dram = delta.subdomainEnergyJ("dram", -1.0);
            pw.printf(CSV_RECORD_FMT, benchmark, pkg, core, uncore, dram, elapsed, pkg0TemperatureAfter, peakRssKbAfter);
        }
    }
}
