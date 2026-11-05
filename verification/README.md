# Native AE2S acceptance

Target: Minecraft 1.12.2, Java 25, **Cleanroom 0.4.0 without `-alpha`**.
The compilation SDK is separate from this runtime baseline.

The final packaged artifact passed client and dedicated-server functional
acceptance on this exact baseline. [acceptance.json](acceptance.json) records the
production jar hash, dependency hashes, 5 unit regressions, 2 server-prerequisite
regressions, 12 server checks and 13 client checks. The client checked all owned
item/block models and the real pattern GUI; see [the screenshot](native-pattern-assembly.png).
The prerequisites below are part of this result.

Build:

```sh
./gradlew test reobfJar reobfRuntimeHarnessJar
```

The ordinary jar contains the production addon only. `src/runtimeHarness` builds a
separate verification mod, including automatic creation of an isolated test world.
Do not install the verification mod in an ordinary world.

Run with the exact jars listed in `dependencies.json` in a dependency directory:

```sh
JAVA_HOME=/path/to/jdk-25 python3 verification/run.py server --dependencies /path/to/dependencies
DISPLAY=:94 LIBGL_ALWAYS_SOFTWARE=1 JAVA_HOME=/path/to/jdk-25 python3 verification/run.py client --dependencies /path/to/dependencies
```

`launch.py` verifies the official installer and runtime-library checksums. It uses
production Minecraft and SRG mod jars. It needs the Minecraft assets/metadata
cache prepared by CleanroomGradle. `MMCE_VERIFICATION_CACHE` can select a shared
runtime library cache. Each scenario writes isolated worlds, `runtime.json`, full
logs, `verification-report.json` and `result.json` below `build/verification`.
Only a successful gameplay report on the exact loader, a normal process exit and
no timeout yield `FUNCTIONAL_PASS`.

Checks cover actual in-world AE2S node capability discovery; powered item/fluid
insertion; partial capacity and exact remainders; reconnect retries; buffered NBT;
slot 36 in the native crafting index and terminal contract; native mixed
item/fluid merged pushes with quantities above `Integer.MAX_VALUE`; rejecting an
overflow without accepting any other ingredients; pattern index refresh; absent
optional resource integrations; an RF resource-key fixture exercising a real
MMCE worker through the synchronous task queue and an atomic insufficient-resource
rejection; a real MMCE machine consuming native pattern
inputs and returning its recipe output; and node cleanup. The client additionally
opens the server-backed 120-slot assembly GUI through `PatternContainer`, checks
pattern synchronization, renders a screenshot and shift-clicks the pattern out.

## Exact baseline prerequisites

MMCE 2.3.2 uses the legacy `appeng` ABI. This does not make AE2UEL interchangeable
with AE2S: **AppliedCompatibility is required by MMCE itself**. The locked runtime
uses the Cleanroom 0.4.0-compatible build associated with
[AppliedCompatibility PR 16](https://github.com/FormlessDragon/AppliedCompatibility/pull/16),
not the unmodified 1.0.1 release. The compile API remains the official 1.0.1 dev
jar. Runtime hashes in `dependencies.json` identify the tested prerequisites;
acceptance must be rerun when changing them.

The unmodified Cleanroom 0.4.0 dedicated server has a separate Kirino discovery
failure even with an empty mods directory. Server acceptance includes the small
`Cleanroom-0.4.0-Server-Compatibility-1.0.0.jar` workaround. Its source and two regression tests are archived
in `server-compat-source` and its exact hash is locked. Build it from the repository
root with `./gradlew -p verification/server-compat-source test jar`. It gates the one discovery
call on the exact version and dedicated-server side; the official loader jar and
version string remain unchanged. This is an installation prerequisite for the
reported dedicated-server result, not evidence that the unpatched loader starts.

The required MMCE, CraftTweaker2, HEI and GeckoLib 3 jars are also locked and run in
both client and server acceptance. The RF fixture validates the native adapter schema and worker scheduling; it is
not a test of an installed AE2Enhanced distribution. Optional AE2Enhanced/Botania/
Astral Sorcery/Thaumic Energistics combinations are outside the base acceptance claim.
