# Native AE2S verification

Target: Minecraft 1.12.2, Java 25, **Cleanroom 0.4.0 without `-alpha`**.
The compilation SDK is separate from this runtime baseline.

The addon implements its AE2S integration directly. There is no
AppliedCompatibility compile/runtime dependency, no bundled legacy AE2 classes,
and no loader transformer. All required runtime dependencies in
[dependencies.json](dependencies.json) are unmodified official releases.
MMCE's optional legacy AE2 devices and mixins remain disabled when the legacy
`appliedenergistics2` mod is absent; this addon registers its own native devices.

The packaged addon passes 5 unit regressions and 15 client/integrated-server
functional checks on the exact baseline. [acceptance.json](acceptance.json)
records the scope, artifact hashes and results of these automated checks.
The user also confirmed normal operation in their tested runtime environment.

Build:

```sh
./gradlew test reobfJar reobfRuntimeHarnessJar
```

The ordinary jar contains only the production addon. `src/runtimeHarness` builds
a separate verification mod, including automatic creation of an isolated test
world. Do not install the verification mod in an ordinary world.

Put the exact official jars listed in `dependencies.json` in a dependency
directory, then run:

```sh
JAVA_HOME=/path/to/jdk-25 python3 verification/run.py server --dependencies /path/to/dependencies
DISPLAY=:94 LIBGL_ALWAYS_SOFTWARE=1 JAVA_HOME=/path/to/jdk-25 python3 verification/run.py client --dependencies /path/to/dependencies
```

`launch.py` verifies the official installer and runtime-library checksums and
uses production Minecraft and SRG mod jars. It needs the Minecraft
assets/metadata cache prepared by CleanroomGradle. `MMCE_VERIFICATION_CACHE` can
select a shared runtime library cache. Each scenario writes isolated worlds,
`runtime.json`, full logs, `verification-report.json` and `result.json` below
`build/verification`. A successful gameplay report on the exact loader, normal
process exit and no timeout are required for `FUNCTIONAL_PASS`.

Checks cover startup without a legacy AE2 bridge; actual AE2S node capability
discovery; powered item/fluid insertion; partial capacity and exact remainders;
reconnect retries; buffered NBT; slot 36 in the native crafting index and terminal
contract; mixed item/fluid merged pushes with quantities above
`Integer.MAX_VALUE`; atomic overflow rejection; pattern index refresh; absent
optional integrations; an RF key fixture exercising a real MMCE worker through
its synchronous task queue; a real MMCE machine consuming native pattern inputs
and returning its recipe output; native hatch administration and command
classloading; and node cleanup. The client opens the server-backed 120-slot
assembly GUI through `PatternContainer`, checks synchronization and all owned
models, renders a screenshot and shift-clicks the pattern out.

The RF fixture verifies the adapter's key schema and worker scheduling. It is
not a test of an installed AE2Enhanced distribution. Optional AE2Enhanced,
Botania, Astral Sorcery and Thaumic Energistics combinations are outside the base
verification claim.
