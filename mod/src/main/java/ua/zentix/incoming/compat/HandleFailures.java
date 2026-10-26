package ua.zentix.incoming.compat;

import java.lang.reflect.UndeclaredThrowableException;

/** Propagation bridge for MethodHandle.invoke's mandatory Throwable declaration. */
public final class HandleFailures {
    private HandleFailures() {}

    /** Errors retain their identity; an undeclared checked Throwable is never a recoverable adapter failure. */
    public static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) throw error;
        return new UndeclaredThrowableException(failure);
    }
}
