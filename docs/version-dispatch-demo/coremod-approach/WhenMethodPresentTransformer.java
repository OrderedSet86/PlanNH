package com.sbancuz.plannh.core;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-hosted {@code @Optional.Method}-style machinery, but capability-based instead of
 * presence-based. Deliberately restricted to DELETING and RENAMING methods — never editing
 * instruction lists — which is why {@code ClassWriter(0)} is safe (existing stack frames stay
 * valid) and why it should stay that restricted.
 *
 * Every annotated class logs its decision exactly once at first load; a missing log line means
 * this transformer isn't registered and defaults are running silently.
 */
public class WhenMethodPresentTransformer implements IClassTransformer {

    private static final Logger LOG = LogManager.getLogger("PlanNH-Core");
    private static final String ANNOTATION_DESC = "Lcom/sbancuz/plannh/core/WhenMethodPresent;";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        // Cheap pre-filter: transformers see EVERY class load. Only our own classes carry the
        // annotation. (The core package itself is transformer-excluded by PlanNHCore.)
        if (basicClass == null || !transformedName.startsWith("com.sbancuz.plannh.")) {
            return basicClass;
        }

        ClassNode cls = new ClassNode();
        new ClassReader(basicClass).accept(cls, 0);

        List<MethodNode> variants = new ArrayList<>();
        for (MethodNode m : cls.methods) {
            if (findAnnotation(m) != null) variants.add(m);
        }
        if (variants.isEmpty()) return basicClass;

        for (MethodNode variant : variants) {
            Map<String, Object> values = toMap(findAnnotation(variant).values);
            String owner = (String) values.get("owner");
            String method = (String) values.get("method");
            String target = (String) values.get("implement");

            cls.methods.remove(variant);
            if (!methodExists(owner, method)) {
                LOG.info("{}: keeping default {}, {}#{} not present",
                        transformedName, target, owner, method);
                continue;
            }

            MethodNode defaultImpl = null;
            for (MethodNode m : cls.methods) {
                if (m.name.equals(target) && m.desc.equals(variant.desc)) {
                    defaultImpl = m;
                    break;
                }
            }
            if (defaultImpl == null) {
                LOG.error("{}: @WhenMethodPresent on {} names missing default {}{}",
                        transformedName, variant.name, target, variant.desc);
                continue;
            }

            cls.methods.remove(defaultImpl);
            variant.name = target;
            // Callers were compile-linked against the default's visibility; the variant is
            // typically private. Skipping this line = IllegalAccessError from any external call.
            variant.access = defaultImpl.access;
            variant.invisibleAnnotations = null;
            cls.methods.add(variant);
            LOG.info("{}: {} replaced by variant ({}#{} present)",
                    transformedName, target, owner, method);
        }

        ClassWriter writer = new ClassWriter(0); // delete/rename only: existing frames stay valid
        cls.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Capability probe: raw class bytes off the mod-jar search path — no classloading, safe at
     * any launch phase (which is what frees annotated classes from all load-order rules).
     * Returns false when the class (or the whole mod) is absent, merging "old version" and
     * "not installed" into one case.
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

    private static AnnotationNode findAnnotation(MethodNode m) {
        if (m.invisibleAnnotations == null) return null; // CLASS retention = "invisible" in ASM
        for (AnnotationNode a : m.invisibleAnnotations) {
            if (ANNOTATION_DESC.equals(a.desc)) return a;
        }
        return null;
    }

    /** Bytecode only records EXPLICITLY written members — defaults never appear, values may be null. */
    private static Map<String, Object> toMap(List<Object> values) {
        Map<String, Object> map = new HashMap<>();
        if (values == null) return map;
        for (int i = 0; i < values.size(); i += 2) {
            map.put((String) values.get(i), values.get(i + 1));
        }
        return map;
    }
}
