package org.dacapo.profiling;

import one.profiler.AsyncProfiler;
import one.profiler.Events;
import org.dacapo.harness.Callback;
import org.dacapo.harness.CommandLineArgs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

public class AsyncProfilerCallback extends Callback {
  private static final String DEFAULT_OUTPUT_FORMAT = OutputFormats.FLAMEGRAPH;
  private static final String DEFAULT_OUTPUT_DIR = ".";
  private static final String DEFAULT_PROFILE_EVENT = Events.CPU;
  private static final String DEFAULT_OPTIONS = "";

  private AsyncProfiler profiler;
  private boolean profilerIsRunning;
  private String outputFormat;
  private Path outputDir;
  private String profileEvent;
  private String fileExtension;

  // Raw, comma-separated async-profiler options appended to the 'start' and 'stop' commands.
  // See https://github.com/async-profiler/async-profiler/blob/v4.4/docs/ProfilerOptions.md
  // Options owned by this callback ('event', 'file' and the output format names) are rejected.
  private String startOptions;
  private String stopOptions;

  public AsyncProfilerCallback(CommandLineArgs args) {
    super(args);
    this.profilerIsRunning = false;
    try {
      this.outputFormat = getOutputFormat();
      this.outputDir = getOutputDir();
      this.profileEvent = getProfileEvent();
      this.startOptions = getOptions(PropertyKeys.START_OPTIONS);
      this.stopOptions = getOptions(PropertyKeys.STOP_OPTIONS);
      this.fileExtension = FileExtensions.fromOutputFormat(outputFormat);
      if (outputFormat.equals(OutputFormats.JFR) && !stopOptions.isEmpty()) {
        System.err.printf(
            "Warning: %s is ignored for JFR output; use %s instead%n",
            PropertyKeys.STOP_OPTIONS, PropertyKeys.START_OPTIONS
        );
      }
      this.profiler = AsyncProfiler.getInstance();
    } catch (Exception | UnsatisfiedLinkError e) {
      printError("load", e);
    }
  }

  private static String getOutputFormat() {
    final String format = System.getProperty(PropertyKeys.OUTPUT_FORMAT, DEFAULT_OUTPUT_FORMAT);
    return format.toLowerCase(Locale.ROOT).trim();
  }

  private static Path getOutputDir() {
    final String dir = System.getProperty(PropertyKeys.OUTPUT_DIR, DEFAULT_OUTPUT_DIR);
    return Paths.get(dir).toAbsolutePath().normalize();
  }

  private static String getOptions(String propertyKey) {
    final StringJoiner options = new StringJoiner(",");

    for (String token : System.getProperty(propertyKey, DEFAULT_OPTIONS).split(",")) {
      final String option = token.trim();
      if (!option.isEmpty()) {
        options.add(requireUnmanaged(option, propertyKey));
      }
    }

    return options.toString();
  }

  private static String requireUnmanaged(String option, String propertyKey) {
    final int eq = option.indexOf('=');
    // async-profiler ignores trailing whitespace and letter case in option names
    final String key = (eq < 0 ? option : option.substring(0, eq)).trim().toLowerCase(Locale.ROOT);

    if (key.equals("event") || key.equals("file") || OutputFormats.ALL.contains(key)) {
      throw new IllegalArgumentException("option '" + key + "' in " + propertyKey + " is managed by the callback");
    }

    return option;
  }

  private static String getProfileEvent() {
    return System.getProperty(PropertyKeys.EVENT, DEFAULT_PROFILE_EVENT);
  }

  private static void printError(String action, Throwable t) {
    System.err.println("Failed to " + action + " async-profiler: " + t.getMessage());
  }

  @Override
  protected void start(String benchmark, boolean warmup) {
    super.start(benchmark, warmup);
    // Profile only the final iteration (!warmup) to capture steady-state JVM performance
    // without warmup noise. This ensures that there is only one output file per benchmark
    // execution, as DaCapo only runs one iteration after the warmup phase.
    if (profiler != null && !profilerIsRunning && !warmup) {
      System.out.println("Starting async-profiler for '" + benchmark + "' benchmark...");
      try {
        // Per https://github.com/async-profiler/async-profiler/blob/v4.4/docs/IntegratingAsyncProfiler.md#using-java-api,
        // 'file' should be specified only once, either in 'start' command with 'jfr' output or
        // in 'stop' command with any other format.
        final String extra = startOptions.isEmpty() ? "" : "," + startOptions;
        final String command = outputFormat.equals(OutputFormats.JFR)
            ? String.format("start,%s,event=%s,file=%s%s", outputFormat, profileEvent, resolveOutputFile(benchmark), extra)
            : String.format("start,event=%s%s", profileEvent, extra);
        final String startupMessage = profiler.execute(command);
        profilerIsRunning = true;
        System.out.println("Async-profiler started with message: " + startupMessage);
      } catch (Exception e) {
        printError("start", e);
      }
    }
  }

  @Override
  protected void complete(String benchmark, boolean valid, boolean warmup) {
    super.complete(benchmark, valid, warmup);
    if (profiler != null && profilerIsRunning) {
      System.out.println("Stopping async-profiler for '" + benchmark + "' benchmark...");
      try {
        final Path outputFile = resolveOutputFile(benchmark);
        // See comment on 'start' command to understand the special handling of 'jfr'
        final String extra = stopOptions.isEmpty() ? "" : "," + stopOptions;
        final String stopCommand = outputFormat.equals(OutputFormats.JFR)
            ? "stop"
            : String.format("stop,%s%s,file=%s", outputFormat, extra, outputFile);
        final String stopMessage = profiler.execute(stopCommand);
        profilerIsRunning = false;
        System.out.println("Async-profiler stopped with message: " + stopMessage);
        System.out.println("Profile saved to '" + outputFile + "'");
      } catch (Exception e) {
        printError("stop", e);
      }
    }
  }

  private Path resolveOutputFile(String benchmark) {
    final String filename = benchmark + "-profile" + fileExtension;
    return outputDir.resolve(filename);
  }

  public static final class PropertyKeys {
    public static final String OUTPUT_FORMAT = "async-profiler.output.fmt";
    public static final String OUTPUT_DIR = "async-profiler.output.dir";
    public static final String EVENT = "async-profiler.event";
    public static final String START_OPTIONS = "async-profiler.start.opts";
    public static final String STOP_OPTIONS = "async-profiler.stop.opts";

    private PropertyKeys() {}
  }

  public static final class FileExtensions {
    public static final String HTML = ".html";
    public static final String JFR = ".jfr";
    public static final String PB = ".pb";
    public static final String TXT = ".txt";

    private FileExtensions() {}

    private static String fromOutputFormat(String outputFormat) {
      Objects.requireNonNull(outputFormat, "outputFormat must not be null");
      switch (outputFormat) {
        case OutputFormats.FLAMEGRAPH:
        case OutputFormats.TREE:
          return HTML;
        case OutputFormats.JFR:
          return JFR;
        case OutputFormats.OTLP:
          return PB;
        default:
          return TXT;
      }
    }
  }

  // Reference: https://github.com/async-profiler/async-profiler/blob/v4.4/docs/ProfilerOptions.md#dump-option
  public static final class OutputFormats {
    public static final String TRACES = "traces";
    public static final String FLAT = "flat";
    public static final String JFR = "jfr";
    public static final String COLLAPSED = "collapsed";
    public static final String FLAMEGRAPH = "flamegraph";
    public static final String TREE = "tree";
    public static final String OTLP = "otlp";

    private static final List<String> ALL = Arrays.asList(TRACES, FLAT, JFR, COLLAPSED, FLAMEGRAPH, TREE, OTLP);

    private OutputFormats() {}
  }
}
