"""Run only ReleasedPampa120CompatibilityTest with cached production classes and current sources.

No Gradle, Android execution, mocks, stubs, network, or dependency downloads.
--parent-classes must identify the coordinator's actual core Kotlin compilation output.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

# Import the sibling recipe without leaving bytecode in the resource directory.
sys.dont_write_bytecode = True
from generate import BASE, cached_jar, digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[5])
    parser.add_argument("--parent-classes", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    parser.add_argument("--android-jar", type=Path, required=True, help="SDK android.jar for resolving existing importer types; no Android methods are executed")
    args = parser.parse_args()
    repo, parent = args.repo.resolve(), args.parent_classes.resolve()
    required_parent = ["importing/PampaBundleArchive.class", "importing/PampaBundleImporter.class",
                       "importing/PampaBundleImporter$Companion.class", "db/AudioPartEntity.class", "db/TranscriptEntity.class"]
    parent_base = parent / "dev/pampa/pampanotes/core"
    parent_hashes = {p: digest((parent_base / p).read_bytes()) for p in required_parent}
    work = Path(tempfile.mkdtemp(prefix="pn3-released120-verify-"))
    print(f"Working directory: {work}", flush=True)
    coordinates = [
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20"), ("org.jetbrains", "annotations", "13.0"),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.6.3"),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.6.3"),
        ("androidx.room", "room-common-jvm", "2.8.4"), ("junit", "junit", "4.13.2"),
        ("org.hamcrest", "hamcrest-core", "1.3"), ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.1"),
        ("javax.inject", "javax.inject", "1"),
    ]
    compiler_coordinates = [
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.20"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.20"), ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.20"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
    ]
    plugin = cached_jar(args.cache, "org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.2.20")
    jars = {coord: cached_jar(args.cache, *coord) for coord in coordinates + compiler_coordinates}
    cp = [str(jars[c]) for c in coordinates]
    # The real Room dependency is used for Kotlin type resolution of parent production classes.
    aars = list((args.cache / "androidx.room/room-runtime-android/2.8.4").glob("*/*.aar"))
    if len(aars) != 1:
        raise RuntimeError("Expected the cached Room runtime AAR")
    room = work / "room-runtime-android-2.8.4.jar"
    with zipfile.ZipFile(aars[0]) as archive:
        room.write_bytes(archive.read("classes.jar"))
    cp += [str(room), str(parent), str(args.android_jar)]
    compiler_cp = os.pathsep.join(str(jars[c]) for c in coordinates[:2] + compiler_coordinates)
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe") if os.name == "nt" and "JAVA_HOME" in os.environ else "java"
    test_relative = "core/src/test/kotlin/dev/pampa/pampanotes/core/importing/ReleasedPampa120CompatibilityTest.kt"
    common = ["importing/PampaBundleMerge.kt", "importing/PampaBundleMetadataMerge.kt",
              "importing/PampaBundleStaging.kt", "importing/ImportFileCopy.kt"]
    extra = ["db/Entities.kt", "model/Ids.kt", "importing/PampaBundleModel.kt", "importing/PampaBundleArchive.kt",
             "importing/PampaNoteContainer.kt", "importing/PampaBundleLegacyReconcile.kt", "importing/PampaBundleBodyMerge.kt",
             "export/PampaNoteWriter.kt", "export/ExportModel.kt", "export/ExportManifest.kt",
             "transcription/VoiceNames.kt", "transcription/WordTimings.kt", "repo/RawTranscripts.kt"]
    # Freeze just these source bytes before either compile; never synthesize replacement classes.
    snapshots = {}
    for relative in [BASE + name for name in common + extra] + [test_relative]:
        source = work / "current" / relative
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_bytes((repo / relative).read_bytes())
        snapshots[relative] = source
    report = {"parentClasses": str(parent), "parentClassSha256": parent_hashes,
              "sourcesSha256": {path: digest(file.read_bytes()) for path, file in snapshots.items()},
              "javaVersion": subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT).decode(),
              "runs": []}
    for name, source_names in [("parent-reader", common), ("current-codec-source", common + extra)]:
        output = work / (name + ".jar")
        paths = [snapshots[BASE + source] for source in source_names] + [snapshots[test_relative]]
        values = ["-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", os.pathsep.join(cp),
                  "-Xfriend-paths=" + str(parent), "-Xplugin=" + str(plugin), "-d", str(output), *map(str, paths)]
        argfile = work / (name + ".args")
        argfile.write_text("\n".join('"' + value.replace("\\", "/") + '"' for value in values), encoding="utf-8")
        compile_cmd = [java, "-Xmx384m", "-XX:ActiveProcessorCount=2", "-cp", compiler_cp,
                       "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + str(argfile)]
        run_cmd = [java, "-Xmx256m", "-XX:ActiveProcessorCount=2", "-cp",
                   os.pathsep.join([str(output), *cp, str(repo / "core/src/test/resources")]), "org.junit.runner.JUnitCore",
                   "dev.pampa.pampanotes.core.importing.ReleasedPampa120CompatibilityTest"]
        run_report = {"name": name, "sourcePaths": [str(p.relative_to(work / "current")) for p in paths],
                      "compileCommand": compile_cmd, "compilerArguments": values, "runCommand": run_cmd}
        for phase, command in [("compile", compile_cmd), ("tests", run_cmd)]:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
            log = work / f"{name}-{phase}.log"
            log.write_bytes(result.stdout)
            run_report[phase] = {"exitCode": result.returncode, "log": result.stdout.decode(errors="replace")}
            print(result.stdout.decode(errors="replace"), end="", flush=True)
            result.check_returncode()
        report["runs"].append(run_report)
    (work / "verification.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Verification and logs: {work}", flush=True)


if __name__ == "__main__":
    main()
