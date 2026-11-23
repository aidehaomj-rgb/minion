#!/usr/bin/env python3
"""纯标准库离线构建 minion-ide-bridge VSIX（无需 npm / vsce / 外网）。"""

import json
import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
PKG = json.loads((ROOT / "package.json").read_text(encoding="utf-8"))
NAME = PKG["name"]
PUBLISHER = PKG["publisher"]
VERSION = PKG["version"]
DISPLAY = PKG.get("displayName", NAME)
DESC = PKG.get("description", "")

CONTENT_TYPES = """<?xml version="1.0" encoding="utf-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension=".json" ContentType="application/json"/>
  <Default Extension=".js" ContentType="application/javascript"/>
  <Default Extension=".md" ContentType="text/markdown"/>
  <Default Extension=".vsixmanifest" ContentType="text/xml"/>
</Types>
"""

MANIFEST = f"""<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011" xmlns:d="http://schemas.microsoft.com/developer/vsx-schema-design/2011">
  <Metadata>
    <Identity Language="en-US" Id="{NAME}" Version="{VERSION}" Publisher="{PUBLISHER}"/>
    <DisplayName>{DISPLAY}</DisplayName>
    <Description xml:space="preserve">{DESC}</Description>
    <Categories>Other</Categories>
  </Metadata>
  <Installation>
    <InstallationTarget Id="Microsoft.VisualStudio.Code"/>
  </Installation>
  <Dependencies/>
  <Assets>
    <Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true"/>
    <Asset Type="Microsoft.VisualStudio.Services.Content.Details" Path="extension/README.md" Addressable="true"/>
  </Assets>
</PackageManifest>
"""


def main() -> None:
    out = ROOT / f"{NAME}-{VERSION}.vsix"
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("[Content_Types].xml", CONTENT_TYPES)
        zf.writestr("extension.vsixmanifest", MANIFEST)
        for rel in ("package.json", "extension.js", "README.md"):
            zf.write(ROOT / rel, f"extension/{rel}")
    print(f"Built offline VSIX: {out}")


if __name__ == "__main__":
    main()
