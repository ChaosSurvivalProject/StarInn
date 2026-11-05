#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
星穹旅驿 · 资源包/分发包构建脚本（开发期工具）

与插件运行时 PackBuilder 同构：jar 内固化 PNG（src/main/resources/resourcepack/）
-> 释放/覆盖 -> zip。本脚本直接从固化目录打包，产物用于预成品下载与基岩导入。

产出：
  1) dist/resource-pack/StarInnResourcePack.zip  —— Java 资源包（pack_format 75 / 1.21.9~1.21.11）
  2) dist/bedrock/StarInnResourcePack.mcpack     —— 基岩资源包（manifest + 同套贴图）
  3) dist/StarInn-1.0.0-bundle.zip               —— 完整分发包（jar + 双端包 + 贴图源图 + 台词 + 文档）

硬性合规检查（基岩 Geyser 转码要求）：
  - 所有贴图宽高为 2 的幂、标准 PNG
  - 资源包内不含 fonts/ shader/ optifine/ 目录
  - 总大小 < 5MB
"""
import hashlib
import json
import os
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RP = os.path.join(ROOT, "src", "main", "resources", "resourcepack")
GUI = os.path.join(RP, "assets", "starinn", "textures", "gui")
DIST = os.path.join(ROOT, "dist")

JAR_NAME = "StarInn-1.0.0.jar"
BUNDLE_NAME = "StarInn-1.0.0-bundle.zip"
PACK_DESC = "「星穹旅驿」新手引导资源包 · 芜/晨/黯立绘"

# 与 Java 端 RESOURCEPACK_FILES 完全一致的固定条目顺序
ENTRIES = [
    ("pack.mcmeta", "pack.mcmeta"),
    ("pack.png", "pack.png"),
    ("assets/starinn/textures/gui/dialogue_bg.png", "assets/starinn/textures/gui/dialogue_bg.png"),
    ("assets/starinn/textures/gui/wu.png", "assets/starinn/textures/gui/wu.png"),
    ("assets/starinn/textures/gui/chen.png", "assets/starinn/textures/gui/chen.png"),
    ("assets/starinn/textures/gui/an.png", "assets/starinn/textures/gui/an.png"),
    ("assets/starinn/textures/gui/bg_inn_dusk.png", "assets/starinn/textures/gui/bg_inn_dusk.png"),
    ("assets/starinn/textures/gui/bg_chirang_day.png", "assets/starinn/textures/gui/bg_chirang_day.png"),
    ("assets/starinn/textures/gui/bg_yuanyu_night.png", "assets/starinn/textures/gui/bg_yuanyu_night.png"),
]

SIZES_POT = {16, 32, 64, 128, 256, 512, 1024, 2048, 4096}
TEXTURES = [rel for rel, _ in ENTRIES if rel.endswith(".png")]


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def png_size(path):
    """直接读 PNG 头取宽高（不依赖第三方库）"""
    with open(path, "rb") as f:
        head = f.read(24)
    assert head[:8] == b"\x89PNG\r\n\x1a\n", "不是标准 PNG: " + path
    assert head[12:16] == b"IHDR", "PNG 缺少 IHDR: " + path
    w = int.from_bytes(head[16:20], "big")
    h = int.from_bytes(head[20:24], "big")
    return w, h


def check_textures():
    """基岩端硬性要求：2 的幂、标准 PNG、总大小 <5MB、无 fonts/shader/optifine"""
    total = 0
    for rel in TEXTURES:
        p = os.path.join(RP, rel)
        assert os.path.isfile(p), "固化贴图缺失：" + rel
        w, h = png_size(p)
        assert w in SIZES_POT and h in SIZES_POT, "%s 尺寸 %dx%d 不是 2 的幂" % (rel, w, h)
        total += os.path.getsize(p)
        print("  %-52s %4dx%-4d %7d 字节" % (rel, w, h, os.path.getsize(p)))
    assert total < 5 * 1024 * 1024, "贴图总大小 %d 超过 5MB" % total
    bad = [d for d in ("fonts", "shader", "shaders", "optifine")
           if os.path.isdir(os.path.join(RP, d))]
    assert not bad, "资源包不允许包含目录: " + ",".join(bad)
    print("贴图检查通过：%d 字节（<5MB），全部 2 的幂、标准 PNG，无 fonts/shader/optifine" % total)


def build_java_pack():
    out_dir = os.path.join(DIST, "resource-pack")
    os.makedirs(out_dir, exist_ok=True)
    zip_path = os.path.join(out_dir, "StarInnResourcePack.zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for rel, arc in ENTRIES:
            z.write(os.path.join(RP, rel), arc)
    print("Java 资源包：", zip_path, os.path.getsize(zip_path), "字节  sha256=", sha256_file(zip_path))
    return zip_path


def build_bedrock_pack():
    out_dir = os.path.join(DIST, "bedrock")
    os.makedirs(out_dir, exist_ok=True)
    mcpack = os.path.join(out_dir, "StarInnResourcePack.mcpack")
    # 固定 UUID（多次构建结果一致；Geyser 端也可直接手动安装）
    header_uuid = "6f0c1e88-25f5-4a53-9b95-5e4f5a6f25f5"
    module_uuid = "2c0f8a3d-9b1e-4f77-9b2a-8f0f6f2f7f21"
    manifest = {
        "format_version": 2,
        "header": {
            "name": "星穹旅驿 资源包",
            "description": PACK_DESC + "（基岩版 / Geyser）",
            "uuid": header_uuid,
            "version": [1, 0, 0],
            "min_engine_version": [1, 21, 0],
        },
        "modules": [{"type": "resources", "uuid": module_uuid, "version": [1, 0, 0]}],
    }
    with zipfile.ZipFile(mcpack, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        z.write(os.path.join(RP, "pack.png"), "pack_icon.png")
        z.write(os.path.join(GUI, "dialogue_bg.png"), "textures/starinn/dialogue_bg.png")
        for n in ("wu", "chen", "an"):
            z.write(os.path.join(GUI, n + ".png"), "textures/starinn/%s.png" % n)
        for b in ("bg_inn_dusk", "bg_chirang_day", "bg_yuanyu_night"):
            z.write(os.path.join(GUI, b + ".png"), "textures/starinn/%s.png" % b)
    print("基岩资源包：", mcpack, os.path.getsize(mcpack), "字节")
    return mcpack


def build_bundle(jar_path, java_zip, mcpack):
    """完整分发包：jar + 双端资源包 + 贴图源图 + 台词三件套 + 文档"""
    bundle = os.path.join(DIST, BUNDLE_NAME)
    base = "StarInn-1.0.0/"
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(jar_path, base + os.path.basename(jar_path))
        z.write(java_zip, base + "resource-pack/StarInnResourcePack.zip")
        z.write(mcpack, base + "bedrock/StarInnResourcePack.mcpack")
        for doc in ("README.md", "QUICKSTART.md", "TESTING.md", "使用说明.txt"):
            p = os.path.join(ROOT, doc)
            if os.path.isfile(p):
                z.write(p, base + doc)
        # 最新台词三件套（老服升级：删掉 plugins/StarInn/dialogues/ 旧文件后放入这些，再 /starinn reload）
        for fn in ("wu.yml", "chen.yml", "an.yml"):
            p = os.path.join(ROOT, "src", "main", "resources", "dialogues", fn)
            z.write(p, base + "dialogues/" + fn)
        for rel in TEXTURES:
            z.write(os.path.join(RP, rel), base + "textures/" + os.path.basename(rel))
    print("分发包：", bundle, os.path.getsize(bundle), "字节")
    return bundle


if __name__ == "__main__":
    print("== 贴图合规检查 ==")
    check_textures()
    java_zip = build_java_pack()
    mcpack = build_bedrock_pack()
    jar_path = os.path.join(ROOT, "build", "libs", JAR_NAME)
    if os.path.isfile(jar_path):
        build_bundle(jar_path, java_zip, mcpack)
    else:
        print("提示：build/libs/%s 不存在，跳过分发包组装（先完成插件编译）" % JAR_NAME)
    print("完成。")
