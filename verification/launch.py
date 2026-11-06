#!/usr/bin/env python3
"""Launch actual SRG installation jars on official Cleanroom 0.4.0, Java 25.

Startup and gameplay evidence are separate: without a harness report, only a
server reaching Done is considered STARTUP_PASS. A client requires a report.
The installation recipe and every library are hash checked against the official
installer; no userdev or development Minecraft jar is placed on this classpath.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import threading
import time
import urllib.request
from zipfile import ZipFile

WORKSPACE = Path(__file__).resolve().parent.parent
VERSION = "0.4.0"
CACHE = Path(os.environ.get("MMCE_VERIFICATION_CACHE", WORKSPACE / "build/verification-runtime"))
GRADLE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
PACK = CACHE / "cleanroom-0.4.0-installer.jar"
PACK_SHA256 = "882435d851a51e14b802e0a9384e4a05cfbb3890fca435c40c494af75e5a166f"


def digest(path, algorithm="sha1"):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def download(url, destination, expected, algorithm="sha1", candidates=()):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and digest(destination, algorithm) == expected:
        return destination
    for candidate in candidates:
        if candidate.is_file() and digest(candidate, algorithm) == expected:
            shutil.copyfile(candidate, destination)
            return destination
    urls = [url]
    if url.startswith("https://repo.cleanroommc.com/releases/"):
        urls.insert(0, url.replace("https://repo.cleanroommc.com/releases/", "https://maven.cleanroommc.com/"))
    last_error = None
    for attempt in range(2):
        for source in urls:
            try:
                request = urllib.request.Request(source, headers={"User-Agent": "AE2-addons-Cleanroom-verification"})
                partial = destination.with_name(destination.name + f".{os.getpid()}.part")
                with urllib.request.urlopen(request, timeout=30) as response, partial.open("wb") as output:
                    shutil.copyfileobj(response, output, 128 * 1024)
                if digest(partial, algorithm) != expected:
                    raise ValueError(f"Hash mismatch: {destination.name}")
                partial.replace(destination)
                print("Downloaded", destination.name, flush=True)
                return destination
            except (OSError, ValueError) as error:
                last_error = error
    raise RuntimeError(f"Could not download {url}: {last_error}")


def allowed(library):
    rules = library.get("rules", [])
    result = not rules
    for rule in rules:
        os_rule = rule.get("os", {})
        if os_rule.get("name", "linux") == "linux" and os_rule.get("arch", "x86_64") in ("x86_64", "amd64"):
            result = rule["action"] == "allow"
    # The historical MMC recipe also lists ARM natives with a plain Linux rule.
    classifier = library["name"].split(":")[3:]
    return result and (not classifier or not classifier[0].startswith("natives-") or classifier == ["natives-linux"])


def artifact(library):
    entry = library["downloads"]["artifact"]
    parts = library["name"].split(":")
    group, module, version = parts[:3]
    path = entry.get("path") or f"{group.replace('.', '/')}/{module}/{version}/{entry['url'].rsplit('/', 1)[-1]}"
    candidates = (GRADLE / "caches/modules-2/files-2.1" / group / module / version).glob(f"*/{Path(path).name}")
    candidates = list(candidates)
    if module == "cleanroom":
        candidates.append(WORKSPACE / ".dev" / f"cleanroom-{VERSION}-universal.jar")
    # Reuse byte-identical libraries from the old verification cache only after SHA-1 validation.
    candidates += list((WORKSPACE / ".dev/cleanroom-0.4.0-runtime/libraries").glob("**/" + Path(path).name))
    if module == "cleanroom":
        entry = dict(entry, url=f"https://maven.cleanroommc.com/com/cleanroommc/cleanroom/{VERSION}/cleanroom-{VERSION}-universal.jar")
    return download(entry["url"], CACHE / "libraries" / path, entry["sha1"], candidates=candidates)


def prepare(side):
    download(f"https://maven.cleanroommc.com/com/cleanroommc/cleanroom/{VERSION}/{PACK.name}", PACK, PACK_SHA256, "sha256")
    with ZipFile(PACK) as archive:
        recipe = json.loads(archive.read("version.json"))
    if recipe["id"] != f"cleanroom-{VERSION}":
        raise ValueError("Unexpected Cleanroom version in the official installer")
    metadata = json.loads((GRADLE / "caches/cleanroom_gradle/versions/1.12.2/meta.json").read_text())
    # Cleanroom replaces vanilla's obsolete LWJGL, Netty, ICU, JNA, logging and utility libraries.
    # Retain only the vanilla audio, authentication and platform services used by the official recipe.
    retained = {"com.paulscode", "com.mojang:authlib", "com.mojang:realms", "com.mojang:text2speech", "ca.weblite:java-objc-bridge"}
    vanilla_libraries = [library for library in metadata["libraries"]
                        if library["name"].split(":")[0] in retained or ":".join(library["name"].split(":")[:2]) in retained]
    libraries = {}
    for group in (recipe["libraries"], vanilla_libraries):
        for library in group:
            if allowed(library):
                libraries.setdefault(library["name"], library)
                native = library.get("natives", {}).get("linux")
                if native:
                    libraries.setdefault(library["name"] + ":" + native, {
                        "name": library["name"] + ":" + native,
                        "downloads": {"artifact": library["downloads"]["classifiers"][native]},
                    })
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        classpath = list(pool.map(artifact, libraries.values()))
    main = metadata["downloads"][side]
    vanilla = GRADLE / f"caches/cleanroom_gradle/versions/1.12.2/{side}.jar"
    classpath.append(download(main["url"], CACHE / f"minecraft-{side}-1.12.2.jar", main["sha1"], candidates=[vanilla]))
    natives = CACHE / "natives"
    natives.mkdir(exist_ok=True)
    for jar in classpath:
        if "natives-linux" in jar.name:
            with ZipFile(jar) as archive:
                for name in archive.namelist():
                    if name.endswith(".so"):
                        target = natives / Path(name).name
                        data = archive.read(name)
                        # Another client may already have this library mapped. Never truncate it in place.
                        if not target.is_file() or target.read_bytes() != data:
                            partial = target.with_name(target.name + f".{os.getpid()}.part")
                            partial.write_bytes(data)
                            partial.replace(target)
    return classpath, natives



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("side", choices=["client", "server"])
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--mod", type=Path, action="append", default=[], help="Actual reobfuscated installation jar")
    parser.add_argument("--name", default="baseline", help="Separate verification scenario directory name")
    parser.add_argument("--port", type=int, default=25694)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--property", action="append", default=[], help="Harness JVM property, key=value")
    parser.add_argument("--report", type=Path, help="Harness-generated JSON, relative to the run directory")
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        parser.error("This installation launcher currently supports Linux x64")
    if not args.name or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for c in args.name):
        parser.error("--name must contain only letters, digits, hyphen and underscore")
    if not 1 <= args.port <= 65535 or args.timeout < 1:
        parser.error("Invalid server port or timeout")
    jars = [jar.resolve(strict=True) for jar in args.mod]
    if len({jar.name for jar in jars}) != len(jars):
        parser.error("Duplicate jar names in installation")
    for jar in jars:
        with ZipFile(jar) as archive:
            if not archive.namelist():
                parser.error(f"Empty jar: {jar}")
        if jar.name.endswith(("-dev.jar", "-sources.jar")):
            parser.error(f"Use a reobfuscated installation jar: {jar}")
    classpath, natives = prepare(args.side)
    if args.prepare_only:
        print(f"Prepared official Cleanroom {VERSION} {args.side}; {len(classpath)} verified libraries", flush=True)
        return
    run = WORKSPACE / "build/verification" / args.name / args.side
    mods = run / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    previous = run / "runtime.json"
    if previous.exists():
        for name in json.loads(previous.read_text()).get("jars", {}):
            (mods / Path(name).name).unlink(missing_ok=True)
    if any(mods.iterdir()):
        raise RuntimeError(f"Unexpected files in verification mods folder: {mods}")
    for jar in jars:
        shutil.copyfile(jar, mods / jar.name)
    config = run / "config"
    config.mkdir(exist_ok=True)
    (config / "splash.properties").write_text("enabled=false\n")
    jdk = Path(os.environ.get("AE2_ADDONS_JAVA_HOME", os.environ.get("JAVA_HOME", "")))
    command = [str(jdk / "bin/java"), "-Xmx3G", "-Dfile.encoding=UTF-8", "-Dfoundation.loadsall=true",
               f"-Djava.library.path={natives}"]
    if args.side == "server":
        # This automated launcher communicates over pipes, using Forge's plain console.
        command += ["-Dterminal.jline=false", "-Dterminal.ansi=false"]
    for prop in args.property:
        if "=" not in prop or prop.startswith("-") or any(c in prop for c in "\r\n"):
            parser.error("Properties must be plain key=value arguments")
        command.append("-D" + prop)
    command += ["-cp", os.pathsep.join(map(str, classpath))]
    if args.side == "client":
        command += ["top.outlands.foundation.boot.Foundation", "--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker",
                    "--username", "AE2AddonDeveloper", "--uuid", "e32af18d-0810-383c-bd28-0cb70e61a70a", "--accessToken", "0",
                    "--version", f"Cleanroom-{VERSION}", "--gameDir", str(run),
                    "--assetsDir", str(GRADLE / "caches/cleanroom_gradle/assets"), "--assetIndex", "1.12",
                    "--width", "1280", "--height", "720"]
    else:
        (run / "eula.txt").write_text("eula=true\n")
        (run / "server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nlevel-name=verification-world\nlevel-type=FLAT\nview-distance=2\ngenerate-structures=false\n")
        command += ["net.minecraftforge.fml.relauncher.ServerLaunchWrapper", "nogui"]
    report = run / args.report if args.report else None
    if report:
        report.unlink(missing_ok=True)
    previous.write_text(json.dumps({"cleanroom": VERSION, "side": args.side, "userdev": False,
        "installer_sha256": PACK_SHA256, "jars": {jar.name: digest(jar, "sha256") for jar in jars},
        "libraries": {str(jar): digest(jar) for jar in classpath}, "command": command}, indent=2) + "\n")
    started = threading.Event()
    ready = threading.Event()
    def capture(stream, target):
        with target.open("w") as log:
            for line in iter(stream.readline, ""):
                log.write(line)
                log.flush()
                if f"Cleanroom version {VERSION}" in line and f"{VERSION}-alpha" not in line:
                    started.set()
                if "Done (" in line and 'For help, type "help"' in line:
                    ready.set()
    print(f"Launching actual Cleanroom {VERSION} {args.side}; evidence: {run}", flush=True)
    process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, errors="replace")
    pump = threading.Thread(target=capture, args=(process.stdout, run / "console.log"), daemon=True)
    pump.start()
    deadline = time.monotonic() + args.timeout
    stopped = False
    timed_out = False
    try:
        while process.poll() is None:
            if args.side == "server" and ready.is_set() and not report and not stopped:
                process.stdin.write("stop\n")
                process.stdin.flush()
                stopped = True
            if time.monotonic() >= deadline:
                timed_out = True
                process.terminate()
                break
            time.sleep(0.2)
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=15)
        pump.join(timeout=5)
    result = {"cleanroom": VERSION, "side": args.side, "exit": process.returncode,
              "loader_version_seen": started.is_set(), "server_ready": ready.is_set(), "timed_out": timed_out}
    if report and report.exists():
        result["gameplay_report"] = json.loads(report.read_text())
        passed = result["gameplay_report"].get("result") == "PASS" and result["gameplay_report"].get("cleanroom") == VERSION
        result["result"] = "FUNCTIONAL_PASS" if passed and started.is_set() and process.returncode == 0 and not timed_out else "FAIL"
    else:
        result["result"] = "STARTUP_PASS" if args.side == "server" and ready.is_set() and started.is_set() and process.returncode == 0 and not timed_out else "FAIL"
    serious = re.findall(r"Pattern registration changed off the owning server thread|Caught an exception when post event|An error occurred during (?:asynchronous|synchronous) task execution!|"
                         r"InvalidInjectionException|MixinApplyError|NoSuchMethodError|NoClassDefFoundError|AbstractMethodError|"
                         r"Failed to process (?:item|fluid|pattern|ME output)|Exception loading (?:model|blockstate) for (?:the )?variant (?:mmceaddition:|modularmachinery:mmceaddition_ae2s_acceptance_controller)", (run / "console.log").read_text())
    if serious:
        result["runtime_errors"] = sorted(set(serious))
        result["result"] = "FAIL"
    (run / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False), flush=True)
    if result["result"] == "FAIL":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
