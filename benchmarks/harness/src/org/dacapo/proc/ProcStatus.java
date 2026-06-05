package org.dacapo.proc;

import org.dacapo.proc.linux.LinuxProcStatus;

import static org.dacapo.harness.util.SystemUtils.IS_OS_LINUX;
import static org.dacapo.harness.util.SystemUtils.OS_NAME;

/**
 * Status information about an operating system process.
 */
public interface ProcStatus {

    /**
     * Returns the process identifier (PID).
     */
    long pid();

    /**
     * Returns the peak resident size (RSS) of the process in kilobytes, or -1 on error.
     */
    long peakRssKb();

    /**
     * Returns a {@code ProcStatus} instance for the current process.
     *
     * @throws UnsupportedOperationException if the current operating system is not supported or the PID cannot be
     *                                       determined in the current environment
     */
    static ProcStatus self() {
        if (IS_OS_LINUX) {
            return LinuxProcStatus.self();
        } else {
            throw new UnsupportedOperationException("Unsupported operating system: " + OS_NAME);
        }
    }
}
