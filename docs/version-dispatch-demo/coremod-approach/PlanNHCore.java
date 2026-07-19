package com.sbancuz.plannh.core;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import net.minecraft.launchwrapper.Launch;

import java.util.Map;

/**
 * Coremod entry point registering the transformer. Wire it up with one line in gradle.properties:
 * {@code coreModClass = core.PlanNHCore} (relative to the mod group) — the GTNH buildscript
 * writes the FMLCorePlugin / FMLCorePluginContainsFMLMod manifest attributes for dev and the
 * built jar.
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
public class PlanNHCore implements IFMLLoadingPlugin {

    public PlanNHCore() {
        // Keep the machinery itself out of the transformer pipeline (and any future recursion).
        Launch.classLoader.addTransformerExclusion("com.sbancuz.plannh.core.");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[] { "com.sbancuz.plannh.core.WhenMethodPresentTransformer" };
    }

    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) {}
    @Override public String getAccessTransformerClass() { return null; }
}
