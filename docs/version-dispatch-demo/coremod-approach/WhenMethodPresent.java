package com.sbancuz.plannh.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a version-specific VARIANT of another method in the same class. At classload,
 * {@code WhenMethodPresentTransformer} probes {@code owner}'s raw bytes for {@code method}:
 * on a hit the default {@code implement} method is deleted and this variant is renamed into its
 * place; on a miss this variant is stripped, so its dependency calls never reach the JVM linker.
 *
 * CLASS retention: the transformer reads it from bytecode ("invisible" annotations in ASM terms);
 * it's never needed at runtime.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface WhenMethodPresent {

    /** Class to probe, e.g. "appeng.api.AEValue". Mod classes only — see transformer javadoc. */
    String owner();

    /** Method whose existence in {@link #owner()}'s jar selects this variant. */
    String method();

    /** Name of the default method (same descriptor, same class) this variant replaces. */
    String implement();
}
