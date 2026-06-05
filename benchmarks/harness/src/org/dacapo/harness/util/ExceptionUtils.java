package org.dacapo.harness.util;

import org.dacapo.harness.function.ThrowingRunnable;

import java.util.Objects;

public final class ExceptionUtils {
    private ExceptionUtils() {
    }

    /**
     * Executes this action safely, accumulating exceptions into {@code rootException}.
     * <p>
     * If an exception occurs and {@code rootException} is {@code null}, the thrown exception is returned.
     * Otherwise, the exception is added to the suppressed exceptions of {@code rootException}, which is then returned.
     *
     * @param action        the throwing action to execute (must not be {@code null})
     * @param rootException the existing accumulated exception (may be {@code null})
     * @return the updated root exception, or {@code null} if no exceptions occurred
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public static Exception runSafely(ThrowingRunnable<?> action, Exception rootException) {
        Objects.requireNonNull(action, "action must not be null");
        try {
            action.run();
        } catch (Exception e) {
            if (rootException == null) {
                rootException = e;
            } else {
                rootException.addSuppressed(e);
            }
        }
        return rootException;
    }
}
