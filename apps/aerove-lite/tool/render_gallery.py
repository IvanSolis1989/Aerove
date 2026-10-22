"""Arrange twelve unmodified Android captures and their provenance in an HTML gallery."""

import argparse
import base64
import hashlib
import html
import json
from pathlib import Path
import re
import struct


def gallery(root, source, output):
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise ValueError("An exact source revision is required")
    candidates = list(root.rglob("dashboard-matrix/manifest.json"))
    if len(candidates) != 1:
        raise ValueError(f"Expected one dashboard matrix manifest, got {len(candidates)}")
    matrix_path = candidates[0]
    matrix = json.loads(matrix_path.read_text(encoding="utf-8"))
    if matrix.get("schema") != 1 or matrix.get("sourceRevision") != source:
        raise ValueError("The matrix must identify this source and supported capture schema")
    if matrix.get("complete") is not True or matrix.get("completedCount") != 12:
        raise ValueError("All twelve actual Android captures must finish successfully")
    if matrix.get("performanceEvidence") is not False or any(matrix.get(key) != 0 for key in ("vpnActions", "networkActions")):
        raise ValueError("The approval fixture must not perform VPN/network actions or claim performance")
    if not re.fullmatch(r"[0-9a-f]{64}", matrix.get("dataSha256", "")):
        raise ValueError("The shared dashboard fixture must have a recorded data hash")
    cases = matrix.get("cases", [])
    if len(cases) != 12 or {case.get("number") for case in cases} != set(range(1, 13)):
        raise ValueError("Expected exactly twelve unique numbered appearances")
    case_by_number = {case["number"]: case for case in cases}
    rows = []
    manifest = []
    dimensions = set()
    for dark in (False, True):
        for aurora in (False, True):
            mode = "dark" if dark else "light"
            background = "aurora" if aurora else "theme"
            caption = f"{'深色' if dark else '浅色'} · {'灵动极光' if aurora else '普通主题'}"
            cells = []
            row = (2 if dark else 0) + (1 if aurora else 0) + 1
            for column, (tier, key, label) in enumerate((("Solid", "solid", "简约纯色"), ("Frosted", "standard", "柔光磨砂"), ("Clear", "ultra", "流光清透")), 1):
                number = (row - 1) * 3 + column
                case = case_by_number[number]
                expected = {"number": number, "row": row, "column": column, "tier": tier, "aurora": aurora, "dark": dark}
                if any(case.get(name) != value for name, value in expected.items()) or case.get("captured") is not True:
                    raise ValueError(f"Incorrect or incomplete appearance {number}")
                stem = f"{number:02d}-{key}-{background}-{mode}"
                if case.get("windowImage") != f"{stem}.png" or case.get("metadata") != f"{stem}.json":
                    raise ValueError(f"Unexpected capture filename for appearance {number}")
                path = matrix_path.parent / case["windowImage"]
                data, digest, size = read_capture(path, case.get("windowSha256"))
                dimensions.add(size)
                if size != (case.get("width"), case.get("height")):
                    raise ValueError(f"Capture dimensions differ from manifest: {path}")
                evidence_path = matrix_path.parent / case["metadata"]
                evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
                if evidence.get("sourceRevision") != source or "PixelCopy" not in evidence.get("method", ""):
                    raise ValueError(f"Capture does not identify this source and Android rendering path: {path}")
                if evidence.get("performanceEvidence") is not False or evidence.get("api", 0) < 33:
                    raise ValueError("Rendering captures require API33+ and cannot claim performance evidence")
                if evidence.get("windowImage") != path.name or evidence.get("windowSha256") != digest:
                    raise ValueError(f"Frame provenance does not match its actual PNG: {path}")
                if (evidence.get("windowWidth"), evidence.get("windowHeight")) != size:
                    raise ValueError(f"Frame metadata dimensions do not match PNG: {path}")
                configuration = evidence.get("configuration", {})
                if any(configuration.get(name) != value for name, value in expected.items()):
                    raise ValueError(f"Frame configuration differs from matrix case {number}")
                screen_name = f"{stem}-screen.png"
                if case.get("displayImage") != screen_name or evidence.get("displayImage") != screen_name:
                    raise ValueError("Missing unmodified display screenshot provenance")
                _, screen_hash, _ = read_capture(matrix_path.parent / screen_name, case.get("displaySha256"))
                if evidence.get("displaySha256") != screen_hash:
                    raise ValueError("Display screenshot hash differs from frame metadata")
                encoded = base64.b64encode(data).decode("ascii")
                cells.append(f'<figure><figcaption>{html.escape(label)}</figcaption><img src="data:image/png;base64,{encoded}" alt="{html.escape(caption + label)}"></figure>')
                manifest.append({"tier": tier, "aurora": aurora, "dark": dark,
                    "capture": str(path), "sha256": digest, "evidence": evidence})
            rows.append(f'<section><h2>{caption}</h2><div class="matrix">{"".join(cells)}</div></section>')
    if len(dimensions) != 1:
        raise ValueError("The approval matrix requires identical capture dimensions")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>AEROVE Lite · 十二种外观</title>
<style>*{box-sizing:border-box}body{margin:0;width:2160px;padding:32px;background:#e8edf3;color:#172f45;font-family:"Segoe UI","Microsoft YaHei",sans-serif}h1{font-size:44px;margin:0 0 10px}p{font-size:24px;margin:0 0 22px}h2{font-size:32px;margin:28px 0 10px}.matrix{display:grid;grid-template-columns:repeat(3,1fr);gap:24px}figure{margin:0;overflow:hidden}figcaption{font-size:28px;margin-bottom:10px}img{display:block;width:100%;height:auto}footer{font-size:20px;margin-top:26px;overflow-wrap:anywhere}</style>
<h1>AEROVE Lite · 十二种外观</h1><p>Android 原生界面实际渲染 · 固定演示数据 · 三档材质 × 两种背景 × 浅色／深色</p>'''
        + "".join(rows) + f'<footer>源码 {source} · PixelCopy 原图按比例排列。模拟器截图不作为真机能耗结果。</footer></html>', encoding="utf-8")
    output.with_suffix(".json").write_text(json.dumps({"source": source, "matrix": matrix, "captures": manifest}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return len(manifest)


def read_capture(path, expected_hash):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n" or len(data) < 24:
        raise ValueError(f"Invalid PNG: {path}")
    size = struct.unpack_from(">II", data, 16)
    if not all(size):
        raise ValueError(f"Empty PNG: {path}")
    digest = hashlib.sha256(data).hexdigest()
    if expected_hash != digest or path.with_name(path.name + ".sha256").read_text().split()[0] != digest:
        raise ValueError(f"Capture SHA256 mismatch: {path}")
    return data, digest, size


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--captures", required=True, type=Path)
    parser.add_argument("--source", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps({"count": gallery(args.captures, args.source, args.output), "gallery": str(args.output)}))
