#!/usr/bin/env python3
"""2.4.8 整 JAR 更新包（Update-Minion.cmd 既有流程）。

本机无 2.4.7 基线 JAR，无法对未知办公室基线做内容补丁小更新；整 JAR 更新对任意
已安装基线生效（校验 SHA-256 → 备份 → 替换 → 失败自动恢复 → Rollback）。
版本规则：docs/update-package-version.txt 必须先递增到 2.4.8；包名/安装脚本名/
备份文件名/内部校验信息/安装说明同版本号。

用法：先 mvn -o package -DskipTests 产出 target/minion-0.1.0.jar，再运行本脚本。
"""
import hashlib
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERSION = "2.4.8"
TOOLS = ROOT / "tools/win7-updater"
JAR = ROOT / "target/minion-0.1.0.jar"
STAMP = datetime.now().strftime("%Y%m%d")
PKG_NAME = "minion-win7-%s-full-%s" % (VERSION, STAMP)
PKG_DIR = ROOT / "dist" / PKG_NAME
ZIP_PATH = ROOT / "dist" / (PKG_NAME + ".zip")
BACKUP_NAME = "minion-0.1.0.jar.pre-%s.bak" % VERSION


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git_head():
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"], cwd=str(ROOT), text=True).strip()
    except Exception:
        return "unknown"


def brand_cmd(src_name, dst_name, title_old, title_new, banner_old, banner_new):
    text = (TOOLS / src_name).read_text(encoding="utf-8")
    if "minion-0.1.0.jar.bak" not in text:
        raise RuntimeError(src_name + " 未找到预期备份名，脚本可能已变化")
    text = text.replace("minion-0.1.0.jar.bak", BACKUP_NAME)
    text = text.replace(title_old, title_new).replace(banner_old, banner_new)
    (PKG_DIR / dst_name).write_text(text, encoding="utf-8", newline="\r\n")


def main():
    version_file = ROOT / "docs/update-package-version.txt"
    declared = version_file.read_text(encoding="utf-8").strip()
    if declared != VERSION:
        raise RuntimeError("docs/update-package-version.txt=%s，须先递增到 %s" % (declared, VERSION))
    if not JAR.is_file():
        raise RuntimeError("缺少 target/minion-0.1.0.jar（先 mvn -o package -DskipTests）")

    if PKG_DIR.exists():
        shutil.rmtree(PKG_DIR)
    (PKG_DIR / "payload").mkdir(parents=True)

    shutil.copy2(JAR, PKG_DIR / "payload/minion-0.1.0.jar")
    jar_hash = sha256_file(PKG_DIR / "payload/minion-0.1.0.jar")
    (PKG_DIR / "payload/minion-0.1.0.jar.sha256").write_text(jar_hash + "\n", encoding="ascii")

    brand_cmd("Update-Minion.cmd", "Update-Minion-%s.cmd" % VERSION,
              "title Minion Updater", "title Minion %s Updater" % VERSION,
              "Minion offline updater", "Minion %s offline updater" % VERSION)
    brand_cmd("Rollback-Minion.cmd", "Rollback-Minion-%s.cmd" % VERSION,
              "title Minion Rollback", "title Minion %s Rollback" % VERSION,
              "Minion offline rollback", "Minion %s offline rollback" % VERSION)

    (PKG_DIR / "version.txt").write_text(
        "version=%s\njar.sha256=%s\nbuilt=%s\ngit=%s\nbaseline=any-installed\n"
        % (VERSION, jar_hash, datetime.now().isoformat(timespec="seconds"), git_head()),
        encoding="ascii")

    readme = """Minion %s 更新包（整 JAR 更新，对任意已安装版本生效）
==========================================================

版本：%s（内部校验见 version.txt 与 payload/minion-0.1.0.jar.sha256）
构建：%s，git %s

本包内容
--------
- 子agent修复：G1 用户补充推进修订后运行中子agent不再从数量/面板/上下文隐身；
  G2 后台子agent高危确认不再持锁挂起堵死主agent（未授权时自动拒绝并给出可操作提示）。
- 子agent运行可视化：聊天流【子Agent】标签实时输出（既有）；任务条折叠摘要新增
  “子Agent N运行”徽标；任务面板子Agent表全程可查。
- 对话级权限选择：输入框发送键左侧“权限”菜单——默认（逐次确认）/自动审批/完全授权。
  选择后本对话主agent与子agent都不再弹确认卡；deny 规则仍然拒绝；仅会话内存态，
  不落盘，重启恢复默认。
- 任务清单条（输入框上方）：待办/子任务/后台任务 1s 轮询实时刷新，追加任务即更新。
- 随包携带此前全部已提交修复（控制环指纹/误停、子agent派发与plan模式、任务修订同步、
  IDE can_use_tool、通知器、E/F 批任务条与 Word 交付显示修复等）。

安装步骤
--------
1. 把整个更新包文件夹带入内网电脑并解压（可直接放进 Minion 安装目录）。
2. 完全关闭 Minion。
3. 双击 Update-Minion-%s.cmd。
4. 更新器自动定位安装目录（找不到时手动输入，例如
   D:\\ai_agent\\minion\\minion-win7-offline-0.1.0）。
5. 确认后：校验 SHA-256 → 备份旧 JAR 到 backup\\%s
   → 替换 → 复核；任一步失败自动恢复旧 JAR。

不修改：jdk8\\、model.json、workspace.json、config.properties、mcp.json、session\\、skills\\

回退
----
关闭 Minion 后双击 Rollback-Minion-%s.cmd，自动恢复 backup\\%s。

验收建议
--------
- 派发后台子agent后追加一句实质要求：任务条/面板仍见运行中子agent，聊天流有实时输出。
- 权限菜单选“自动审批”：主/子agent高危操作不再弹卡；切回“默认”恢复弹卡。
- 子agent跑高危命令且未授权：自动拒绝并提示改只读替代或交主Agent前台执行，主agent不卡死。
""" % (VERSION, VERSION, datetime.now().strftime("%Y-%m-%d %H:%M"), git_head(),
       VERSION, BACKUP_NAME, VERSION, BACKUP_NAME)
    (PKG_DIR / "更新说明.txt").write_text(readme, encoding="utf-8-sig", newline="\r\n")

    if ZIP_PATH.exists():
        ZIP_PATH.unlink()
    with zipfile.ZipFile(ZIP_PATH, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(PKG_DIR.rglob("*")):
            if path.is_file():
                archive.write(path, PKG_NAME + "/" + path.relative_to(PKG_DIR).as_posix())
    zip_hash = sha256_file(ZIP_PATH)
    ZIP_PATH.with_suffix(".zip.sha256").write_text(zip_hash + "\n", encoding="ascii")

    # 自检：zip 完整性 + jar 哈希一致 + 脚本版本/备份名一致
    with zipfile.ZipFile(ZIP_PATH) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("zip 完整性校验失败")
        names = archive.namelist()
        for required in ["payload/minion-0.1.0.jar", "payload/minion-0.1.0.jar.sha256",
                         "Update-Minion-%s.cmd" % VERSION, "Rollback-Minion-%s.cmd" % VERSION,
                         "version.txt", "更新说明.txt"]:
            if PKG_NAME + "/" + required not in names:
                raise RuntimeError("包内缺少 " + required)
        if hashlib.sha256(archive.read(PKG_NAME + "/payload/minion-0.1.0.jar")).hexdigest() != jar_hash:
            raise RuntimeError("包内 JAR 哈希不一致")
        for cmd in ["Update-Minion-%s.cmd" % VERSION, "Rollback-Minion-%s.cmd" % VERSION]:
            body = archive.read(PKG_NAME + "/" + cmd).decode("utf-8")
            if BACKUP_NAME not in body or VERSION not in body:
                raise RuntimeError(cmd + " 版本号/备份名不一致")
            if "minion-0.1.0.jar.bak" in body.replace(BACKUP_NAME, ""):
                raise RuntimeError(cmd + " 残留旧备份名")

    print("PACKAGE OK: %s" % ZIP_PATH)
    print("jar.sha256=%s" % jar_hash)
    print("zip.sha256=%s" % zip_hash)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("PACKAGE FAILED: %s" % error)
        sys.exit(1)