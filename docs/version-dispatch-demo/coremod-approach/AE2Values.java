package com.sbancuz.plannh.compat;

// import appeng.api.AEValue;  // hypothetical class from the question — point at the real one
import com.sbancuz.plannh.core.WhenMethodPresent;

/**
 * Usage — the whole point of the coremod approach: the default is real, readable Java, and every
 * version split costs exactly one annotation line.
 *
 * The variant needs SOME distinct name because Java forbids two same-signature methods in one
 * source class; the transformer renames it to the canonical name in bytecode, where the conflict
 * no longer exists (the default has just been deleted). The {@code $} is not syntax — just a
 * legal identifier character, conventionally reserved for machine-managed names (the compiler
 * uses it for {@code Outer$Inner}, {@code lambda$main$0}, bridges), signaling "not meant to be
 * called by hand-written code". {@code getValuesNew} would work identically.
 *
 * Compile against the NEWEST supported AE2 (single {@code compileOnly} pin) so the variant body
 * is plain and compile-checked; the default references nothing version-specific.
 */
public class AE2Values {

    public static int getValues() {
        return 1; // AE2 <= 2.9.0, or AE2 not installed
    }

    @WhenMethodPresent(owner = "appeng.api.AEValue", method = "getValueFromNewMethod",
                       implement = "getValues")
    private static int getValues$new() {
        return AEValue.getValueFromNewMethod();
    }
}
