#!/usr/bin/env python3
import argparse
import hashlib
import json
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
DEFAULT_BASELINE = ROOT / "target/v66-baseline/minion-0.1.0.jar"
DEFAULT_MANIFEST = ROOT / "target/v66-baseline/v66-entries.tsv"
DEFAULT_TEMPLATES = ROOT / "target/v66-baseline/templates"
DEFAULT_CURRENT = ROOT / "target/minion-0.1.0.jar"


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
    return {name: hashlib.sha256(data).hexdigest() for name, data in entries.items()}


def write_manifest(path, hashes):
    lines = [hashes[name] + "\t" + name for name in sorted(hashes)]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def transform_version(text):
    return (text.replace("V66", "V67")
            .replace("v66", "v67")
            .replace("V65", "V66")
            .replace("v65", "v66"))


def run(command):
    completed = subprocess.run(
        [str(item) for item in command],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return completed.returncode, completed.stdout


def package(args):
    if not args.baseline.is_file():
        raise RuntimeError("V66 baseline JAR is missing")
    if not args.current.is_file():
        raise RuntimeError("Current JAR is missing")
    if not args.templates.is_dir():
        raise RuntimeError("Updater templates are missing")

    baseline_manifest = read_manifest(args.baseline_manifest)
    baseline_entries = read_jar(args.baseline)
    if entry_hashes(baseline_entries) != baseline_manifest:
        raise RuntimeError("V66 baseline JAR does not match v66-entries.tsv")
    target_entries = read_jar(args.current)
    target_manifest = entry_hashes(target_entries)
    changed = sorted(
        name for name, digest in target_manifest.items()
        if baseline_manifest.get(name) != digest
    )
    removed = sorted(set(baseline_manifest) - set(target_manifest))
    if not changed and not removed:
        raise RuntimeError("Current JAR is identical to V66 baseline")

    stamp = datetime.now().strftime("%Y%m%d")
    package_root = args.output_dir / ("v67-package-" + uuid.uuid4().hex[:8])
    package_dir = package_root / "package"
    payload = package_dir / "payload"
    payload.mkdir(parents=True)
    package_root.mkdir(parents=True, exist_ok=True)

    write_manifest(payload / "v66-entries.tsv", baseline_manifest)
    write_manifest(payload / "v67-entries.tsv", target_manifest)
    with zipfile.ZipFile(
        payload / "entries-patch.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=9
    ) as patch:
        for name in changed:
            patch.writestr(name, target_entries[name])

    sources = package_root / "sources"
    sources.mkdir()
    small_update = transform_version(
        (args.templates / "MinionSmallUpdate.java").read_text(encoding="utf-8")
    )
    (sources / "MinionSmallUpdate.java").write_text(small_update, encoding="utf-8")
    shutil.copy2(
        args.templates / "JarPatchApplier.java", sources / "JarPatchApplier.java"
    )
    shutil.copy2(
        ROOT / "tools/win7-updater/JarEntryVerifier.java",
        sources / "JarEntryVerifier.java",
    )

    code, output = run([
        JDK / "bin/javac.exe", "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-d", payload,
        sources / "JarPatchApplier.java",
        sources / "JarEntryVerifier.java",
        sources / "MinionSmallUpdate.java",
    ])
    (package_root / "compile.log").write_text(output, encoding="utf-8")
    if code != 0:
        raise RuntimeError("Updater compilation failed")

    update_cmd = transform_version(
        (args.templates / "Update-Minion-V66-Small.cmd").read_text(encoding="utf-8")
    )
    (package_dir / "Update-Minion-V67-Small.cmd").write_text(
        update_cmd, encoding="utf-8"
    )

    readme = textwrap.dedent("""\
        Minion Win7 V67 小更新（2026-11-16）

        适用：已安装 V66 的 Minion 离线桌面版。

        本版重点：
        - 重试升级为指数退避、抖动并尊重 Retry-After，非 2xx 响应体完整落诊断日志。
        - Edit 匹配失败返回最近匹配行号；领域规则迁移为按需加载的内置技能。
        - 危险命令白名单支持 del /q 等前缀规则，窄化确认范围。
        - token 校准按模型指纹持久化，图片按真实像素尺寸估算。
        - Grep/Glob 尊重 .gitignore，恒跳过 .git 与二进制文件。
        - ripgrep 13.0.0 直接内置于 JAR，首次使用自解压并校验；无外部文件时可回退 Java 遍历。
        - ripgrep 搜索增加默认 120 秒整体截止和强取消：超时或用户停止会终止进程，不再无限等待。
        - 完成门禁裁决抽出 StopAdjudicator，保持既有语义并降低 AgentLoop 复杂度。

        1. 完全退出 Minion，然后完整解压 ZIP。
        2. 双击 Update-Minion-V67-Small.cmd，按提示选择原安装目录（含 minion-0.1.0.jar）。
        3. 输入 Y 确认。看到 V67 update completed successfully 后，使用原来的快捷方式启动。

        本包不是完整安装包。更早版本请先升级到 V66。
        更新仅替换程序 JAR，不覆盖模型配置、项目、对话、知识库、自动化规则、Java 或 Python。
        rg.exe 位于新 JAR 内，升级后首次 Grep/Glob 时自动解压到安装目录 .session/tools。

        旧 JAR 自动备份至原安装目录 backup。需回退时先退出 Minion，将对应的
        minion-0.1.0.jar.pre-v67-*.bak 复制回原安装目录，重命名为 minion-0.1.0.jar。
        如提示基线不是 V66 或校验失败，不要强制覆盖。
        """)
    (package_dir / "安装说明.txt").write_text(readme, encoding="utf-8")

    sidecars = [
        "JarPatchApplier.class",
        "JarEntryVerifier.class",
        "MinionSmallUpdate.class",
        "entries-patch.zip",
        "v66-entries.tsv",
        "v67-entries.tsv",
    ]
    for name in sidecars:
        path = payload / name
        if not path.is_file():
            raise RuntimeError("Missing payload item: " + name)
        (payload / (name + ".sha256")).write_text(
            sha256_file(path) + "\n", encoding="ascii"
        )

    archive_path = package_dir / (
        "minion-win7-v67-small-from-v66-" + stamp + ".zip"
    )
    with zipfile.ZipFile(
        archive_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9
    ) as final_zip:
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
    code, output = run([
        java, "-cp", payload, "MinionSmallUpdate", "check", payload, install
    ])
    logs["check"] = output
    if code != 0:
        raise RuntimeError("V66 baseline check failed")

    code, output = run([
        java, "-cp", payload, "MinionSmallUpdate", "apply", payload, install
    ])
    logs["apply"] = output
    if code != 0:
        raise RuntimeError("V67 apply failed")
    if entry_hashes(read_jar(install / "minion-0.1.0.jar")) != target_manifest:
        raise RuntimeError("Patched JAR does not match V67 manifest")

    with zipfile.ZipFile(install / "minion-0.1.0.jar") as archive:
        rg = archive.read("ripgrep/rg.exe")
        if hashlib.sha256(rg).hexdigest() != (
                "ab5595a4f7a6b918cece0e7e22ebc883ead6163948571419a1dd5cd3c7f37972"):
            raise RuntimeError("Bundled rg.exe hash mismatch")

    code, output = run([
        java, "-cp", payload, "MinionSmallUpdate", "apply", payload, install
    ])
    logs["repeat"] = output
    if code != 0 or "Already V67" not in output:
        raise RuntimeError("Repeated V67 update was not idempotent")

    for name, before in data_hashes.items():
        if sha256_file(install / name) != before:
            raise RuntimeError("User data changed during update: " + name)

    unknown = package_root / "verify/unknown"
    unknown.mkdir(parents=True)
    unknown_entries = read_jar(args.baseline)
    first = sorted(unknown_entries)[0]
    unknown_entries[first] = unknown_entries[first] + b"\0"
    with zipfile.ZipFile(
        unknown / "minion-0.1.0.jar", "w", zipfile.ZIP_DEFLATED
    ) as altered:
        for name, data in unknown_entries.items():
            altered.writestr(name, data)
    code, output = run([
        java, "-cp", payload, "MinionSmallUpdate", "check", payload, unknown
    ])
    logs["unsupported"] = output
    if code == 0:
        raise RuntimeError("Unsupported baseline was accepted")

    corrupt = package_root / "verify/corrupt-payload"
    shutil.copytree(payload, corrupt)
    corrupt_entry = corrupt / "entries-patch.zip"
    corrupt_entry.write_bytes(corrupt_entry.read_bytes() + b"corrupt")
    code, output = run([
        java, "-cp", corrupt, "MinionSmallUpdate", "verify", corrupt
    ])
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
        "baselines": ["v66"],
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
        "bundledRipgrep": "verified",
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
