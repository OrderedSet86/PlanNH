package com.sbancuz.plannh.mixins.late;

// import appeng.api.AEValue;  // hypothetical class from the question — point at the real one
import com.sbancuz.plannh.compat.AE2Values;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * The new-API variant. Only registered by {@code PlanNHLateMixins} when the probe finds the new
 * method in the installed AE2 jar, so on old AE2 the call below is never merged anywhere and
 * never reaches the JVM linker (Mixin reads this class as bytes; it is never classloaded).
 *
 * Compile against the NEWEST supported AE2 (single {@code compileOnly} pin) so this call is
 * plain and compile-checked. {@code remap = false} because the target is our own, never-obfuscated
 * class. Never reference this class from normal code — that's a Mixin error.
 *
 * Signature and visibility deliberately match the target's {@code public static} exactly;
 * {@code @Overwrite} merges in place, so visibility can never go wrong here.
 */
@Mixin(value = AE2Values.class, remap = false)
public class AE2ValuesNewMixin {

    /**
     * @author sbancuz
     * @reason AE2 with the new API exposes the value directly
     */
    @Overwrite
    public static int getValues() {
        return AEValue.getValueFromNewMethod();
    }
}
