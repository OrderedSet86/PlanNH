package com.sbancuz.plannh.core;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The entire version-dispatch decision lives here: probe the installed AE2 jar's bytes for the
 * new API method, and register the overwrite mixin only on a hit.
 *
 * This must be a LATE mixin loader: the target is a mod class, and mod jars aren't on the
 * classpath at coremod time — an early probe would return null and silently pick the old branch.
 *
 * Every launch logs the decision exactly once; a MISSING log line means the mixin infra isn't
 * registered (build misconfiguration) and the default path is running silently.
 */
@LateMixin
public class PlanNHLateMixins implements ILateMixinLoader {

    private static final Logger LOG = LogManager.getLogger("PlanNH");

    @Override
    public String getMixinConfig() {
        return "mixins.plannh.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> mixins = new ArrayList<>();
        boolean newApi = methodExists("appeng.api.AEValue", "getValueFromNewMethod");
        LOG.info("AE2 dispatch: {}", newApi ? "applying new-API mixin" : "keeping default (old AE2 or absent)");
        if (newApi) mixins.add("AE2ValuesNewMixin"); // name relative to the config's package
        return mixins;
    }

    /**
     * Capability probe: raw class bytes off the mod-jar search path — no classloading, so it
     * can't break mixin targets or trip load-order landmines. Returns false when the class (or
     * the whole mod) is absent, which merges "old version" and "not installed" into one case.
     *
     * Limits: bytes are untransformed (a method ASM-injected by another coremod is invisible)
     * and unremapped (probe mod classes only, never obfuscated vanilla/Forge classes).
     */
    private static boolean methodExists(String owner, String method) {
        try {
            byte[] bytes = Launch.classLoader.getClassBytes(owner);
            if (bytes == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode m : node.methods) {
                if (m.name.equals(method)) return true;
            }
        } catch (IOException ignored) {}
        return false;
    }
}
