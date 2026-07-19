# Version dispatch for dependencies (the "AE2 2.9.0 vs newer" problem)

This folder answers the question:

> Does anyone know if there is a way to make a method for a specific version of a dependency, like
> `int getValuesForAE2v290() { return 1; }` vs `int getValuesForAE2() { return AEValue.getValueFromNewMethod(); }`

Java has no language-level mechanism for this — the JVM links the method you compiled against,
and if it's missing at runtime you get `NoSuchMethodError`. Forge's `@Optional.Method` looks like
the answer but only keys on mod *presence*, never version (and no Forge version ever added one;
1.13+ deleted `@Optional` entirely). The two workable designs are in the subfolders, complete and
final. Both build on the same two ideas:

1. **Classloading isolation.** The JVM resolves a method reference when the *calling class* is
   linked, not when the line runs. So code touching the version-specific API must live in a
   class/bytecode blob that only gets loaded/merged on the matching branch.
2. **Capability probing, not version strings.** Both designs decide by reading the dependency's
   raw class bytes (`Launch.classLoader.getClassBytes`) and checking whether the new method
   physically exists. This has no load-order constraints (no waiting for FML's mod list), treats
   "old version" and "mod absent" as the same case for free, and survives forks/backports where
   the version string lies.

These files are **reference code, not wired into this repo's build** — they live under `docs/` so
you can read and copy them without them compiling here. `appeng.api.AEValue` /
`getValueFromNewMethod` are the hypothetical class/method from the question; point the probe and
the variant body at the real ones. Package names are `com.sbancuz.plannh.*` so they drop into
place if you tinker in-tree.

## TL;DR — which one to use

| | [`mixin-approach/`](mixin-approach/) | [`coremod-approach/`](coremod-approach/) |
|---|---|---|
| Per version-split cost | 1 mixin class + 1 `mixins.add(...)` line | 1 annotation line, same file as the default |
| One-time infrastructure | ~20-line probe in the late loader; Mixin does all patching | ~80-line hand-rolled coremod transformer |
| Risk surface | Minimal — battle-tested Mixin merging | You own bytecode surgery (mitigated: delete/rename only) |
| Build wiring | `usesMixins = true`, `mixinsPackage = mixins.late` | `coreModClass = core.PlanNHCore` |
| Good when | You have (or will have) mixins anyway; 1–4 splits | Splits multiply and you want `@Optional`-style one-liners without adopting mixins |

**Recommendation: the mixin approach**, unless you're allergic to mixins or expect many splits
and want annotation ergonomics. Both end at "the default path is plain readable Java, the new
path applies itself only when the installed jar actually has the method."

There is also a **zero-infrastructure option** not in a subfolder because it's ten lines: keep
both paths in one class, look the new method up once via
`MethodHandles.lookup().findStatic(...)` (null on `NoSuchMethodException` → old path). Right
answer for a single split if you want no build changes at all. Its downside is the new path loses
compile checking and stack traces get a reflective frame.

## How each works, in one paragraph

**Mixin:** `AE2Values.getValues()` is real Java returning the old value. A late mixin loader
(`ILateMixinLoader` via UniMixins/gtnhmixins) probes the AE2 jar's bytes at startup; if the new
method exists, it registers `AE2ValuesNewMixin`, whose `@Overwrite` body (a plain, compile-checked
call against the one AE2 jar you compile with) is merged into `AE2Values` at classload. Mixin
reads the mixin class as bytes — it is never classloaded — so when it isn't applied, the new
method reference never reaches the linker. It must be a **late** mixin: the target is a mod class,
and mod jars aren't on the classpath at coremod time, so an early probe would return null and
silently pick the old branch.

**Coremod annotation:** same default-body idea, but the variant lives in the same class under a
placeholder name (`getValues$new` — `$` is just a legal identifier character, conventionally
reserved for machine-managed names) with `@WhenMethodPresent(owner, method, implement)` on it. A
~80-line `IClassTransformer` runs at classload: probe hit → delete the default, rename the variant
to the canonical name, **copying the default's access flags** (skipping that causes
`IllegalAccessError`: private variant behind a public name); probe miss → strip the variant, so
its AE2 call is never linked. Write-once, then every future split anywhere in the mod is one
annotation line. This is exactly the machinery behind FML's `@Optional.Method`, self-hosted.

## Shared weaknesses (inherent to both)

- **The probe sees untransformed bytes.** A method ASM-injected into AE2 by *another* coremod is
  invisible to `getClassBytes`. Probe only methods that genuinely ship in the jar.
- **Mod classes only.** `getClassBytes` doesn't remap, so probing obfuscated vanilla/Forge classes
  by MCP name fails in production. Fine for AE2/any mod API.
- **Decision is per-launch, once per class.** A class transforms once; whatever branch is chosen
  at first load is permanent for that launch. Harmless — jars can't change mid-game.
- **Silent-wrong-branch failure mode.** If the infra isn't registered (build misconfig), the
  default path runs silently. Both designs log their decision exactly once
  (`"applying new-API mixin"` / `"keeping default"` / `"replaced by variant"`); a **missing log
  line is the diagnostic**.
- **Compile against exactly one jar.** Pin the *newest* supported dependency version as a single
  `compileOnly` in `dependencies.gradle` (e.g.
  `compileOnly('com.github.GTNewHorizons:Applied-Energistics-2-Unofficial:2.9.1:dev')`). The
  version split never appears in Gradle — that's the whole trick. The new-path code is
  compile-checked; the old path references nothing version-specific so it needs no jar at all.

## Approach-specific weaknesses

**Mixin:** drags in the mixin toolchain if you don't already have it; the mixin class must never
be referenced from normal code; `@Overwrite` clobbers the whole method body, so if the split ever
becomes "call new API for part of the logic", switch to `@Inject`/`@Redirect` or extract the
version-specific part into its own tiny method and overwrite that.

**Coremod:** you maintain the transformer; coremods complicate debugging (breakpoints in
transformed classes, `-Dfml.coreMods.load` quirks in odd dev setups — the GTNH buildscript's
`coreModClass` handles the normal case); the `AssertionError`-stub safety net was traded away for
the simpler default-body design, so the misconfiguration failure is the silent default path (see
the log-line diagnostic above). The transformer is deliberately restricted to deleting and
renaming methods — never editing instructions — which is why `ClassWriter(0)` is safe (existing
stack frames stay valid) and why it should stay that restricted.

## If you want the version-string flavor instead

Swap the probe for FML's comparator:

```java
ModContainer ae2 = Loader.instance().getIndexedModList().get("appliedenergistics2");
boolean newApi = ae2 != null
    && VersionParser.parseRange("[2.9.1,)").containsVersion(ae2.getProcessedVersion());
```

This reintroduces a timing constraint (the mod list must be populated when the check runs — true
for late mixins, NOT true early in launch or for classes loaded before init in the coremod
design), and a separate `isModLoaded`-style absent check. The capability probe was chosen because
it deletes both problems; use version ranges only when the API changed *behavior* without changing
*shape* (same method, different semantics — presence can't detect that).

## Verification checklist (either approach)

Launch three configurations: old AE2, new AE2, no AE2. Each time: confirm the single decision log
line picks the right branch, call the method **from outside its class** (exercises
visibility/linkage), and confirm no `NoSuchMethodError` / `IllegalAccessError`.
