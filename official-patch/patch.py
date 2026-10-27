#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""
把预载模块（hgtv.preload.Hooks）的调用插进 apktool 解开的官方 HGTV。

找连接点只靠「平台接口的固定特征」，不靠混淆后的名字：
  StreamInfo 类   ← toString 里的 "StreamInfo(url="
  Range 类        ← toString 里的 "Range(bytes="
  Detail 类       ← toString 里的 "Detail(series="
  取播放地址方法   ← 签名 (String, int) -> StreamInfo，且方法体含 "spade_a"
  分段读取方法     ← 签名 (long, long) -> Range，且方法体含 "https://novel.snssdk.com/"
  取详情方法       ← 签名 (String) -> Detail
任何一项找不到或找到不止一个，就直接失败（宁可不出包，也不出一个改坏的包）。

用法：patch.py <apktool 解包目录> [--package com.hgtv.star.preload] [--label "HGTV 预载版"]
"""
import argparse
import pathlib
import re
import sys

HOOKS = "Lhgtv/preload/Hooks;"


def die(msg):
    print(f"::error::{msg}")
    sys.exit(1)


def smali_files(root):
    return [p for d in sorted(root.glob("smali*")) for p in d.rglob("*.smali")]


def class_name(text):
    m = re.search(r"^\.class [^\n]*?(L[^;\s]+;)", text, re.M)
    return m.group(1) if m else None


def find_class_by_string(files, needle):
    hits = []
    for path, text in files.items():
        if f'"{needle}' in text:
            hits.append(class_name(text))
    hits = sorted(set(filter(None, hits)))
    if len(hits) != 1:
        die(f'特征字串 "{needle}" 对应 {len(hits)} 个类：{hits}')
    return hits[0]


METHOD_RE = re.compile(r"^\.method ([^\n]*?)(\S+)\(([^)]*)\)(\S+)\n(.*?)^\.end method", re.M | re.S)


def find_methods(files, params, ret, body_needle=None):
    found = []
    for path, text in files.items():
        for m in METHOD_RE.finditer(text):
            flags, name, desc, rtype, body = m.groups()
            if desc == params and rtype == ret and (body_needle is None or body_needle in body):
                found.append((path, name, "static" in flags.split(), m.start(), m.end()))
    return found


def first_code_index(lines):
    """方法体里第一条指令 / 标签的位置（跳过 .locals、.param、.annotation 等指令块）。"""
    depth = None
    for i, line in enumerate(lines):
        s = line.strip()
        if depth:
            if s == depth:
                depth = None
            continue
        if s.startswith(".annotation"):
            depth = ".end annotation"; continue
        if s.startswith(".param") and not s.endswith(".end param"):
            # .param 可能单行，也可能带块；带块时以 .end param 结束
            if i + 1 < len(lines) and lines[i + 1].strip().startswith(".annotation"):
                depth = ".end param"
            continue
        if not s or s.startswith(".") or s.startswith("#"):
            continue
        return i
    die("方法里找不到任何指令")


def patch_method(text, start, end, before, after_call, label):
    """before：入口代码（会拿到临时寄存器名 {t}）；after_call：每个 return-object 前插入的调用（{r} = 返回寄存器）。"""
    method = text[start:end]
    lines = method.split("\n")
    loc = next((i for i, l in enumerate(lines) if l.strip().startswith(".locals ")), None)
    if loc is None:
        die("方法没有 .locals（不支持 .registers 写法）")
    count = int(lines[loc].split()[1])
    temp = f"v{count}"
    lines[loc] = re.sub(r"\.locals \d+", f".locals {count + 1}", lines[loc])
    out = []
    code_at = first_code_index(lines)
    for i, line in enumerate(lines):
        if i == code_at and before:
            out.extend("    " + l for l in before.format(t=temp, label=label).split("\n"))
            out.append("")
        m = re.match(r"^\s*return-object ([vp]\d+)\s*$", line)
        if m and after_call:
            out.append("    " + after_call.format(r=m.group(1)))
            out.append("")
        out.append(line)
    return text[:start] + "\n".join(out) + text[end:]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root")
    ap.add_argument("--package", default="com.hgtv.star.preload")
    ap.add_argument("--label", default="HGTV 预载版")
    args = ap.parse_args()
    root = pathlib.Path(args.root)
    files = {p: p.read_text(encoding="utf-8") for p in smali_files(root)}
    if not files:
        die("找不到 smali 文件")

    stream_cls = find_class_by_string(files, "StreamInfo(url=")
    range_cls = find_class_by_string(files, "Range(bytes=")
    detail_cls = find_class_by_string(files, "Detail(series=")
    print(f"StreamInfo={stream_cls} Range={range_cls} Detail={detail_cls}")

    stream = find_methods(files, "Ljava/lang/String;I", stream_cls, '"spade_a"')
    ranges = find_methods(files, "JJ", range_cls, '"https://novel.snssdk.com/"')
    details = find_methods(files, "Ljava/lang/String;", detail_cls)
    if len(stream) != 1 or stream[0][2]:
        die(f"取播放地址的方法应恰好 1 个且非静态，实际：{[(s[1], s[2]) for s in stream]}")
    if len(ranges) != 1 or ranges[0][2]:
        die(f"分段读取的方法应恰好 1 个且非静态，实际：{[(r[1], r[2]) for r in ranges]}")
    if not details:
        die("找不到取详情的方法")
    print(f"stream={stream[0][0].name}->{stream[0][1]} range={ranges[0][0].name}->{ranges[0][1]} detail={[(d[0].name, d[1]) for d in details]}")

    plans = []
    p, name, _, s, e = stream[0]
    plans.append((p, s, e,
        "invoke-static/range {{p0 .. p2}}, " + HOOKS + "->streamBefore(Ljava/lang/Object;Ljava/lang/String;I)Ljava/lang/Object;\n"
        "move-result-object {t}\n"
        "if-eqz {t}, :{label}\n"
        f"check-cast {{t}}, {stream_cls}\n"
        "return-object {t}\n"
        ":{label}",
        "invoke-static/range {{{r} .. {r}}}, " + HOOKS + "->streamAfter(Ljava/lang/Object;)V",
        "hgtv_preload_stream_miss"))
    p, name, _, s, e = ranges[0]
    plans.append((p, s, e,
        "invoke-static/range {{p0 .. p4}}, " + HOOKS + "->rangeBefore(Ljava/lang/Object;JJ)Ljava/lang/Object;\n"
        "move-result-object {t}\n"
        "if-eqz {t}, :{label}\n"
        f"check-cast {{t}}, {range_cls}\n"
        "return-object {t}\n"
        ":{label}",
        "invoke-static/range {{{r} .. {r}}}, " + HOOKS + "->rangeAfter(Ljava/lang/Object;)V",
        "hgtv_preload_range_miss"))
    for p, name, static, s, e in details:
        before = "" if static else "invoke-static/range {{p0 .. p0}}, " + HOOKS + "->repoSeen(Ljava/lang/Object;)V"
        plans.append((p, s, e, before, "invoke-static/range {{{r} .. {r}}}, " + HOOKS + "->detailAfter(Ljava/lang/Object;)V", ""))

    # 同一个文件里有多处要改时，从后往前改，前面的偏移才不会变。
    by_file = {}
    for plan in plans:
        by_file.setdefault(plan[0], []).append(plan)
    for path, items in by_file.items():
        text = files[path]
        for _, s, e, before, after, label in sorted(items, key=lambda x: -x[1]):
            text = patch_method(text, s, e, before, after, label)
        path.write_text(text, encoding="utf-8"); files[path] = text
        print(f"patched {path.relative_to(root)} ({len(items)} 处)")

    # 与官方版隔离：官方把登录态 / 设备身份镜像到 /sdcard/.hgtv（重装恢复用），
    # 两个 App 共用就会把官方的登录带进预载版。预载版改用自己的目录，默认保持匿名。
    isolated = 0
    for path, text in list(files.items()):
        new = text
        new = re.sub(r'(const-string(?:/jumbo)? [vp]\d+, )"\.hgtv"', r'\1".hgtv-preload"', new)
        new = new.replace('"com.hgtv.star.action.', '"com.hgtv.star.preload.action.')
        if new != text:
            path.write_text(new, encoding="utf-8"); files[path] = new; isolated += 1
    print(f"isolated {isolated} 个文件（.hgtv 目录 / 广播名）")
    if isolated == 0:
        print("::warning::没找到 .hgtv 目录字串：预载版可能会和官方版共用登录态")
    # 官方的 baseline profile 对应的是原 dex，改过之后对不上，删掉免得白做优化。
    import shutil
    shutil.rmtree(root / "assets" / "dexopt", ignore_errors=True)

    # 包名与名称：和官方版并存。
    yml = root / "apktool.yml"
    y = yml.read_text(encoding="utf-8")
    y = re.sub(r"renameManifestPackage: .*", f"renameManifestPackage: {args.package}", y)
    y = re.sub(r"(versionName: )(.*)", lambda m: m.group(1) + m.group(2).strip().strip("'\"") + "-preload", y)
    yml.write_text(y, encoding="utf-8")
    manifest = root / "AndroidManifest.xml"
    mtext = manifest.read_text(encoding="utf-8")
    mtext, n = re.subn(r'(<application[^>]*?android:label=")[^"]*(")', lambda m: m.group(1) + args.label + m.group(2), mtext, count=1)
    if n != 1:
        print("::warning::application 的 label 不是写死的字串，名称沿用官方")
    if 'android:authorities="' in mtext:
        mtext = re.sub(r'android:authorities="([^"]+)"', lambda m: f'android:authorities="{m.group(1)}.preload"', mtext)
    manifest.write_text(mtext, encoding="utf-8")
    print("done")


if __name__ == "__main__":
    main()
