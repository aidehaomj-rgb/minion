#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import textwrap
import uuid
import zipfile
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JDK = ROOT / "target/build-tools-memory/zulu8.40.0.25-ca-fx-jdk8.0.222-win_x64"
DEFAULT_BASELINE = ROOT / "target/v64-baseline/minion-0.1.0.jar"
DEFAULT_MANIFEST = ROOT / "target/v64-baseline/v64-entries.tsv"
DEFAULT_TEMPLATES = ROOT / "target/v64-baseline/templates"
DEFAULT_CURRENT = ROOT / "target/minion-0.1.0.jar"


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_jar(path):
    with zipfile.ZipFile(path) as archive:
        return {
            name: archive.read(name)
            for name in archive.namelist()
            if not name.endswith("/")
        }


def read_manifest(path):
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        if len(line) < 66 or line[64] != "\t":
            raise RuntimeError("Invalid manifest line: " + line[:80])
        result[line[65:]] = line[:64]
    return result


def entry_hashes(entries):
    return {name: sha256_bytes(data) for name, data in entries.items()}


def write_manifest(path, hashes):
    lines = [hashes[name] + "\t" + name for name in sorted(hashes)]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def transform_version(text):
    return (text.replace("V64", "V65")
            .replace("v64", "v65")
            .replace("V63", "V64")
            .replace("v63", "v64"))


def run(command, cwd=None):
    completed = subprocess.run(
        [str(item) for item in command],
        cwd=str(cwd) if cwd else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return completed.returncode, completed.stdout


def package(args):
    if not args.baseline.is_file():
        raise RuntimeError("V64 baseline JAR is missing")
    if not args.current.is_file():
        raise RuntimeError("Current JAR is missing")
    if not args.templates.is_dir():
        raise RuntimeError("Updater templates are missing")

    baseline_manifest = read_manifest(args.baseline_manifest)
    baseline_entries = read_jar(args.baseline)
    if entry_hashes(baseline_entries) != baseline_manifest:
        raise RuntimeError("V64 baseline JAR does not match v64-entries.tsv")
    target_entries = read_jar(args.current)
    target_manifest = entry_hashes(target_entries)
    changed = sorted(
        name for name, digest in target_manifest.items()
        if baseline_manifest.get(name) != digest
    )
    removed = sorted(set(baseline_manifest) - set(target_manifest))
    if not changed and not removed:
        raise RuntimeError("Current JAR is identical to V64 baseline")

    stamp = datetime.now().strftime("%Y%m%d")
    package_root = args.output_dir / ("v65-package-" + uuid.uuid4().hex[:8])
    package_dir = package_root / "package"
    payload = package_dir / "payload"
    payload.mkdir(parents=True)
    package_root.mkdir(parents=True, exist_ok=True)

    write_manifest(payload / "v64-entries.tsv", baseline_manifest)
    write_manifest(payload / "v65-entries.tsv", target_manifest)

    patch_path = payload / "entries-patch.zip"
    with zipfile.ZipFile(patch_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as patch:
        for name in changed:
            patch.writestr(name, target_entries[name])

    sources = package_root / "sources"
    sources.mkdir()
    small_update = transform_version(
        (args.templates / "MinionSmallUpdate.java").read_text(encoding="utf-8")
    )
    (sources / "MinionSmallUpdate.java").write_text(small_update, encoding="utf-8", newline="\n")
    shutil.copy2(args.templates / "JarPatchApplier.java", sources / "JarPatchApplier.java")
    shutil.copy2(ROOT / "tools/win7-updater/JarEntryVerifier.java",
                 sources / "JarEntryVerifier.java")

    javac = JDK / "bin/javac.exe"
    code, output = run([
        javac, "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-d", payload,
        sources / "JarPatchApplier.java",
        sources / "JarEntryVerifier.java",
        sources / "MinionSmallUpdate.java",
    ])
    (package_root / "compile.log").write_text(output, encoding="utf-8")
    if code != 0:
        raise RuntimeError("Updater compilation failed")

    update_cmd = transform_version(
        (args.templates / "Update-Minion-V64-Small.cmd").read_text(encoding="utf-8")
    )
    (package_dir / "Update-Minion-V65-Small.cmd").write_text(
        update_cmd, encoding="utf-8", newline="\r\n"
    )

    readme = transform_version(
        (args.templates / "安装说明.txt").read_text(encoding="utf-8")
    )
    readme = readme.replace(
        "Minion Win7 V65 小更新（2026-11-14）",
        "Minion Win7 V65 小更新（2026-11-16）",
        1,
    )
    readme = readme.replace(
        "适用：已安装 V64 的 Minion 离线桌面版。",
        textwrap.dedent("""\
        适用：已安装 V64 的 Minion 离线桌面版。

        本版重点：
        - 修复长对话自动续接、纯思考空响应和阶段性回复误停。
        - 修复子 Agent 文件核验、Todo 上下文和输出合并。
        - 统一 Windows 路径真实身份，兼容符号链接/盘符别名。
        - 修复成果工作区引用文件展示和检查点并发。
        """).strip(),
        1,
    )
    (package_dir / "安装说明.txt").write_text(readme, encoding="utf-8", newline="\r\n")

    sidecars = [
        "JarPatchApplier.class",
        "JarEntryVerifier.class",
        "MinionSmallUpdate.class",
        "entries-patch.zip",
        "v64-entries.tsv",
        "v65-entries.tsv",
    ]
    for name in sidecars:
        path = payload / name
        if not path.is_file():
            raise RuntimeError("Missing payload item: " + name)
        (payload / (name + ".sha256")).write_text(
            sha256_file(path) + "\n", encoding="ascii", newline="\n"
        )

    archive_path = package_dir / (
        "minion-win7-v65-small-from-v64-" + stamp + ".zip"
    )
    with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as final_zip:
        for path in sorted(package_dir.rglob("*")):
            if path.is_file() and path != archive_path:
                final_zip.write(path, path.relative_to(package_dir).as_posix())

    verify(package_root, package_dir, payload, args, target_manifest, changed, removed)

    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    dist_zip = dist / archive_path.name
    shutil.copy2(archive_path, dist_zip)
    return package_root, dist_zip


def verify(package_root, package_dir, payload, args, target_manifest, changed, removed):
    java = JDK / "bin/java.exe"
    install = package_root / "verify/install"
    install.mkdir(parents=True)
    shutil.copy2(args.baseline, install / "minion-0.1.0.jar")
    (install / "config.properties").write_text("preserve=config\n", encoding="utf-8")
    (install / "workspace.json").write_text("preserve=workspace\n", encoding="utf-8")
    fixture = install / "session/default"
    fixture.mkdir(parents=True)
    (fixture / "keep.json").write_text("preserve=session\n", encoding="utf-8")
    data_hashes = {
        name: sha256_file(install / name)
        for name in ["config.properties", "workspace.json", "session/default/keep.json"]
    }

    logs = {}
    code, output = run([java, "-cp", payload, "MinionSmallUpdate", "check", payload, install])
    logs["check"] = output
    if code != 0:
        raise RuntimeError("V65 baseline check failed")

    code, output = run([java, "-cp", payload, "MinionSmallUpdate", "apply", payload, install])
    logs["apply"] = output
    if code != 0:
        raise RuntimeError("V65 apply failed")
    if entry_hashes(read_jar(install / "minion-0.1.0.jar")) != target_manifest:
        raise RuntimeError("Patched JAR does not match V65 manifest")

    code, output = run([java, "-cp", payload, "MinionSmallUpdate", "apply", payload, install])
    logs["repeat"] = output
    if code != 0 or "Already V65" not in output:
        raise RuntimeError("Repeated V65 update was not idempotent")

    for name, before in data_hashes.items():
        if sha256_file(install / name) != before:
            raise RuntimeError("User data changed during update: " + name)

    unknown = package_root / "verify/unknown"
    unknown.mkdir(parents=True)
    unknown_entries = read_jar(args.baseline)
    first = sorted(unknown_entries)[0]
    unknown_entries[first] = unknown_entries[first] + b"\0"
    with zipfile.ZipFile(unknown / "minion-0.1.0.jar", "w", zipfile.ZIP_DEFLATED) as altered:
        for name, data in unknown_entries.items():
            altered.writestr(name, data)
    code, output = run([java, "-cp", payload, "MinionSmallUpdate", "check", payload, unknown])
    logs["unsupported"] = output
    if code == 0:
        raise RuntimeError("Unsupported baseline was accepted")

    corrupt = package_root / "verify/corrupt-payload"
    shutil.copytree(payload, corrupt)
    corrupt_entry = corrupt / "entries-patch.zip"
    corrupt_entry.write_bytes(corrupt_entry.read_bytes() + b"corrupt")
    code, output = run([java, "-cp", corrupt, "MinionSmallUpdate", "verify", corrupt])
    logs["corrupt"] = output
    if code == 0:
        raise RuntimeError("Corrupt payload was accepted")

    for name, text in logs.items():
        (package_root / ("verify-" + name + ".log")).write_text(text, encoding="utf-8")

    archive = next(package_dir.glob("*.zip"))
    verified = {
        "file": archive.name,
        "sha256": sha256_file(archive),
        "size": archive.stat().st_size,
        "baselines": ["v64"],
        "changedEntries": len(changed),
        "removedEntries": len(removed),
        "targetJarEntries": len(target_manifest),
        "baseline": str(args.baseline),
        "baselineSha256": sha256_file(args.baseline),
        "upgrade": "passed",
        "repeat": "passed",
        "wrongVersion": "rejected",
        "corruptPayload": "rejected",
        "dataPreservation": "passed",
        "uiSmoke": "not run by packaging script",
    }
    (package_root / "verified.json").write_text(
        json.dumps(verified, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--baseline-manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--templates", type=Path, default=DEFAULT_TEMPLATES)
    parser.add_argument("--current", type=Path, default=DEFAULT_CURRENT)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "target")
    args = parser.parse_args()
    package_root, dist_zip = package(args)
    print(str(package_root))
    print(str(dist_zip))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("PACKAGE FAILED: " + str(error), file=sys.stderr)
        sys.exit(1)
