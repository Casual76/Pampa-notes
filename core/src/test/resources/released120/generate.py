"""Offline, standalone generation with byte-for-byte released Kotlin sources; no Gradle.

Run from any checkout containing the local release tag. Writes only a fresh TEMP directory.
The output ZIP is never rewritten or normalized after BundleWriter closes it.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

TAG = "stable-pampa-notes-v1.2.0"
COMMIT = "e8c9ba4f2d0b7cc4a5f138e622be62da61979845"
BASE = "core/src/main/kotlin/dev/pampa/pampanotes/core/"
SOURCES = [
    "db/Entities.kt", "model/Ids.kt", "importing/PampaBundleModel.kt",
    "export/BundleLayout.kt", "export/BundleWriter.kt", "export/ChapterLines.kt",
    "export/ExportLabels.kt", "export/ExportManifest.kt", "export/ExportModel.kt",
    "export/IndexWriter.kt", "export/MarkdownWriter.kt", "export/PampaBundleExport.kt",
    "export/SkillWriter.kt", "transcription/TranscriptParagraphs.kt",
    "transcription/Chapters.kt", "transcription/VoiceNames.kt",
]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args])


def cached_jar(cache, group, artifact, version):
    files = list((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
    if len(files) != 1:
        raise RuntimeError(f"Expected one cached {group}:{artifact}:{version}, found {len(files)}; no download attempted")
    return files[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[5])
    parser.add_argument("--cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    args = parser.parse_args()
    repo = args.repo.resolve()
    resources = Path(__file__).resolve().parent
    actual_commit = git(repo, "rev-parse", TAG + "^{commit}").decode().strip()
    if actual_commit != COMMIT:
        raise RuntimeError(f"Unexpected tag target {actual_commit}")
    work = Path(tempfile.mkdtemp(prefix="pn3-released120-generate-"))
    print(f"Working directory: {work}", flush=True)
    provenance = {"tag": TAG, "commit": COMMIT, "sources": []}
    paths = []
    for relative in SOURCES:
        path = BASE + relative
        data = git(repo, "show", f"{COMMIT}:{path}")
        target = work / "released" / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        paths.append(target)
        provenance["sources"].append({"path": path, "gitBlob": git(repo, "rev-parse", f"{COMMIT}:{path}").decode().strip(), "sha256": digest(data)})
    for path in ["gradle/libs.versions.toml", "app/build.gradle"]:
        data = git(repo, "show", f"{COMMIT}:{path}")
        provenance.setdefault("releaseMetadata", []).append({"path": path, "gitBlob": git(repo, "rev-parse", f"{COMMIT}:{path}").decode().strip(), "sha256": digest(data)})
    coordinates = [
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20"),
        ("org.jetbrains", "annotations", "13.0"),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.6.3"),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.6.3"),
        ("androidx.room", "room-common-jvm", "2.8.4"),
    ]
    compiler_coordinates = [
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.20"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.20"),
        ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.20"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
    ]
    plugin_coord = ("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable", "2.2.20")
    jars = {coord: cached_jar(args.cache, *coord) for coord in coordinates + compiler_coordinates + [plugin_coord]}
    provenance["cachedDependencies"] = [{"coordinate": ":".join(coord), "sha256": digest(path.read_bytes())} for coord, path in jars.items()]
    classpath = os.pathsep.join(str(jars[c]) for c in coordinates)
    compiler_cp = os.pathsep.join(str(jars[c]) for c in coordinates[:2] + compiler_coordinates)
    generator = work / "Released120Fixture.kt"
    generator.write_text((resources / (generator.name + ".txt")).read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
    provenance["generatorSha256"] = digest(generator.read_bytes())
    inputs = work / "input"
    inputs.mkdir()
    tone = repo / "core/src/test/resources/pn/tone.m4a"
    expected_tone = "1ba6b5929c65382d145c0887726a3deba6d81f528dcf590aed88f29642556452"
    if digest(tone.read_bytes()) != expected_tone:
        raise RuntimeError("Unexpected synthetic tone bytes")
    shutil.copyfile(tone, inputs / "tone.m4a")
    output = work / "released120-synthetic.zip"
    jar = work / "released-writer.jar"
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe") if os.name == "nt" and "JAVA_HOME" in os.environ else "java"
    compile_args = ["-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
                    f"-Xplugin={jars[plugin_coord]}", "-d", str(jar), *map(str, paths), str(generator)]
    # Kotlin's argument-file grammar accepts forward slashes on Windows, including quoted spaces.
    args_file = work / "compile.args"
    args_file.write_text("\n".join('"' + value.replace("\\", "/") + '"' for value in compile_args), encoding="utf-8")
    compile_cmd = [java, "-Xmx384m", "-XX:ActiveProcessorCount=2", "-cp", compiler_cp,
                   "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + str(args_file)]
    run_cmd = [java, "-Xmx192m", "-XX:ActiveProcessorCount=2", "-Duser.timezone=UTC", "-Duser.language=en", "-Duser.country=US",
               "-cp", str(jar) + os.pathsep + classpath, "Released120FixtureKt", str(inputs), str(output)]
    provenance["commands"] = {"compile": compile_cmd, "run": run_cmd}
    provenance["javaVersion"] = subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT).decode()
    for name, command in [("compile", compile_cmd), ("generate", run_cmd)]:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
        (work / (name + ".log")).write_bytes(result.stdout)
        print(result.stdout.decode(errors="replace"), end="", flush=True)
        result.check_returncode()
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("Invalid generated ZIP CRC")
        provenance["entries"] = [{"path": item.filename, "bytes": item.file_size, "sha256": digest(archive.read(item))}
                                 for item in archive.infolist() if not item.is_dir()]
    provenance["archive"] = {"file": output.name, "bytes": output.stat().st_size, "sha256": digest(output.read_bytes())}
    (work / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print(f"ZIP and provenance: {work}", flush=True)


if __name__ == "__main__":
    main()
