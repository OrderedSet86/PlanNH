package com.sbancuz.plannh.compat;

/**
 * The dispatch target. The body below is the REAL default behavior (old AE2, or AE2 absent) —
 * plain, compile-checked, debuggable Java. When the installed AE2 jar has the new API method,
 * {@code AE2ValuesNewMixin} overwrites {@link #getValues()} at classload; otherwise this body
 * runs untouched. Callers just call {@code AE2Values.getValues()} and never know a split exists.
 */
public class AE2Values {

    public static int getValues() {
        return 1; // AE2 <= 2.9.0, or AE2 not installed
    }
}
