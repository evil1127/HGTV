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


def find_methods_prefix(files, prefix, ret, body_needle=None):
    found = []
    for path, text in files.items():
        for m in METHOD_RE.finditer(text):
            flags, name, desc, rtype, body = m.groups()
            if desc.startswith(prefix) and rtype == ret and (body_needle is None or body_needle in body):
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


def tune_stall_recovery(files):
    """连线卡死时更快重连（1.2.9.1 实测：缓冲停住 30 秒才清连线池，清完 2 秒内就恢复）。
    - 影片用的 OkHttp：连线 6 秒、读取 8 秒没资料就断、整个请求最多 25 秒（原本 8~15 / 30 / 45）。
    - 「缓冲停止前进」看门狗：30 秒 → 10 秒，触发后官方会清连线池并在原位置重载。
    找不到就只警告，不影响出包。"""
    timeout_re = re.compile(
        r"(const-wide/16 (v\d+), )0x(8|f|3)(\s+invoke-virtual \{[^}]+\}, (L[^;]+;)->b\(J(?:Ljava/util/concurrent/TimeUnit;)?\)V\s+)"
        r"(const-wide/16 (v\d+), )0x1e(\s+invoke-virtual \{[^}]+\}, \5->d\(J(?:Ljava/util/concurrent/TimeUnit;)?\)V\s+)"
        r"(const-wide/16 (v\d+), )0x2d(\s+invoke-virtual \{[^}]+\}, \5->a\(J(?:Ljava/util/concurrent/TimeUnit;)?\)V)")
    clients = 0
    for path, text in list(files.items()):
        # 连线逾时：作者原本 3 秒的就保留 3 秒（1.3.x 影片用的那支），其余改 6 秒；读取 8 秒、整个请求 25 秒
        new, n = timeout_re.subn(lambda m: m.group(1) + ("0x3" if m.group(3) == "3" else "0x6") + m.group(4) + m.group(6) + "0x8" + m.group(8) + m.group(9) + "0x19" + m.group(11), text)
        if n:
            path.write_text(new, encoding="utf-8"); files[path] = new; clients += n
    # 1.3.5：影片那支的连线逾时沿用前面的暂存器（没有自己的 const），只认「读取 30 / 整个请求 45」
    timeout2_re = re.compile(
        r"(invoke-virtual \{[^}]+\}, (L[^;]+;)->b\(JLjava/util/concurrent/TimeUnit;\)V\s+)"
        r"(const-wide/16 (v\d+), )0x1e(\s+invoke-virtual \{[^}]+\}, \2->d\(JLjava/util/concurrent/TimeUnit;\)V\s+)"
        r"(const-wide/16 (v\d+), )0x2d(\s+invoke-virtual \{[^}]+\}, \2->a\(JLjava/util/concurrent/TimeUnit;\)V)")
    for path, text in list(files.items()):
        new, n = timeout2_re.subn(lambda m: m.group(1) + m.group(3) + "0x8" + m.group(5) + m.group(6) + "0x19" + m.group(8), text)
        if n:
            path.write_text(new, encoding="utf-8"); files[path] = new; clients += n
    report(clients > 0, f"影片连线逾时缩短（{clients} 处）" if clients else "影片连线逾时缩短：没找到逾时设定")

    stall_msg = "\\u64ad\\u653e\\u4e2d\\u7f13\\u51b2\\u505c\\u6b62\\u524d\\u8fdb 30 \\u79d2"
    stall = 0
    for path, text in list(files.items()):
        if stall_msg not in text:
            continue
        for m in METHOD_RE.finditer(text):
            body = m.group(5)
            if stall_msg not in body:
                continue
            nb, n = re.subn(r"(const-wide/16 v\d+, )0x7530\b", r"\g<1>0x2710", body)
            nb = nb.replace(stall_msg, stall_msg.replace(" 30 ", " 10 "))
            if n == 1:
                text = text[:m.start(5)] + nb + text[m.end(5):]
                path.write_text(text, encoding="utf-8"); files[path] = text; stall += 1
            break
    report(stall > 0, "缓冲卡住 10 秒就重连" if stall else "缓冲卡住看门狗：没找到")


def tune_buffer(files):
    """播放缓冲加大（不降画质）：作者 1.2.9.1 是 min 20 秒 / max 60 秒 / 卡住后只等 2 秒就续播 / 24 MB。
    短剧一集约 90 秒，改成整集先载完（min 90 秒、max 120 秒），卡住后先存 6 秒再播，容量 64 MB。
    找不到就只警告。"""
    marker = '"bufferForPlaybackAfterRebufferMs"'
    done = 0
    for path, text in list(files.items()):
        if marker not in text or '"maxBufferMs"' not in text:
            continue
        for m in METHOD_RE.finditer(text):
            body = m.group(5)
            if marker not in body or '"maxBufferMs"' not in body:
                continue
            nb, a = re.subn(r"const/16 (v\d+), 0x7d0\b", r"const/16 \1, 0x1770", body, count=1)      # 卡住后续播 2 秒 → 6 秒
            nb, b = re.subn(r"const/16 (v\d+), 0x4e20\b", r"const \1, 0x15f90", nb, count=1)         # min 20 秒 → 90 秒
            nb, c = re.subn(r"const (v\d+), 0xea60\b", r"const \1, 0x1d4c0", nb, count=1)            # max 60 秒 → 120 秒
            nb, d = re.subn(r"const/high16 (v\d+), 0x1800000\b", r"const/high16 \1, 0x4000000", nb, count=1)  # 24 MB → 64 MB
            if a and b and c:
                text = text[:m.start(5)] + nb + text[m.end(5):]
                path.write_text(text, encoding="utf-8"); files[path] = text; done += 1
                report(True, "播放缓冲加大（整集 / 卡住后先存 6 秒 / 64 MB）")
            break
    if done == 0:
        report(False, "播放缓冲加大：没找到缓冲设定")

REPORT = []


def report(ok, text):
    """记一项修补结果；最后写进 patch-report.md，发布说明会附上（哪项没套上一眼就看得到）。"""
    REPORT.append(("✅ " if ok else "⚠️ ") + text)
    if not ok:
        print(f"::warning::{text}")


def method_bodies(files, pred):
    """回传 [(path, match)]：pred(path, text, match) 为真的所有方法。"""
    out = []
    for path, text in files.items():
        for m in METHOD_RE.finditer(text):
            if pred(path, text, m):
                out.append((path, m))
    return out


def insert_at_method_start(files, path, m, code):
    text = files[path]
    body = m.group(5)
    lines = body.split("\n")
    idx = first_code_index(lines)
    lines.insert(idx, "    " + code + "\n")
    nb = "\n".join(lines)
    text = text[:m.start(5)] + nb + text[m.end(5):]
    path.write_text(text, encoding="utf-8"); files[path] = text


def add_fix_hooks(files):
    # 1. Application.onCreate 最前面：启动清理 + 诊断页
    apps = [p for p, x in files.items() if re.search(r"^\.super Landroid/app/Application;", x, re.M)]
    done = False
    if len(apps) == 1:
        hits = method_bodies({apps[0]: files[apps[0]]}, lambda p, x, m: m.group(2) == "onCreate" and m.group(3) == "" and m.group(4) == "V")
        if len(hits) == 1:
            insert_at_method_start(files, hits[0][0], hits[0][1], "invoke-static/range {p0 .. p0}, " + HOOKS + "->onAppStart(Ljava/lang/Object;)V")
            done = True
    report(done, "启动清理（DNS 档 / 播放暂存：地址、开头、索引）+ 诊断页 :18321/diag" + ("" if done else "：找不到 Application.onCreate"))

    # 2. 作者的 DNS：查询方法最前面认出快取；「改问大陆公共 DNS」那支记下来 60 秒后作废
    dns_files = [p for p, x in files.items() if "Broken system behaviour for dns lookup of" in x and '"dns public host="' in x]
    ok_seen = ok_pub = False
    if len(dns_files) == 1:
        path = dns_files[0]
        hits = method_bodies({path: files[path]}, lambda p, x, m: "static" not in m.group(1).split() and m.group(3) == "Ljava/lang/String;" and m.group(4) == "Ljava/util/List;" and "dns public host=" in m.group(5))
        if len(hits) == 1:
            insert_at_method_start(files, path, hits[0][1], "invoke-static/range {p0 .. p0}, " + HOOKS + "->dnsSeen(Ljava/lang/Object;)V")
            ok_seen = True
            text = files[path]
            pub = re.compile(r'(const-string (v\d+), "dns public host="\s+invoke-direct \{(v\d+), \2\}, Ljava/lang/StringBuilder;-><init>\(Ljava/lang/String;\)V\s+invoke-virtual \{\3, (v\d+)\}, Ljava/lang/StringBuilder;->append\(Ljava/lang/String;\)Ljava/lang/StringBuilder;)')
            new, n = pub.subn(lambda mm: mm.group(1) + "\n\n    invoke-static/range {" + mm.group(4) + " .. " + mm.group(4) + "}, " + HOOKS + "->dnsPublic(Ljava/lang/String;)V", text)
            if n == 1:
                path.write_text(new, encoding="utf-8"); files[path] = new; ok_pub = True
    report(ok_seen, "失败时清 DNS（认出作者的 DNS 快取）" + ("" if ok_seen else "：找不到作者的 DNS 查询方法"))
    report(ok_pub, "大陆公共 DNS 的结果 60 秒后作废" + ("" if ok_pub else "：找不到「dns public host=」那一支"))

    # 3. 播放失败上报：static (MainActivity, Throwable, String)V
    hits = method_bodies(files, lambda p, x, m: "static" in m.group(1).split() and re.fullmatch(r"L[^;]*MainActivity;Ljava/lang/Throwable;Ljava/lang/String;", m.group(3) or "") and m.group(4) == "V")
    ok = len(hits) == 1
    if ok:
        insert_at_method_start(files, hits[0][0], hits[0][1], "invoke-static/range {p1 .. p2}, " + HOOKS + "->onFailure(Ljava/lang/Throwable;Ljava/lang/String;)V")
    report(ok, "播放失败时清这一集的伺服器 DNS / 未完成暂存；连续失败清全部" + ("" if ok else f"：失败上报方法找到 {len(hits)} 个"))

    # 4. 主 / 备线路轮替：解析 main_url 之前
    pick_re = re.compile(r'(\n(\s*)const-string (v\d+), "main_url"\s+invoke-static \{(v\d+), \3\}, L[^;]+;->\w+\(L[^;]+;Ljava/lang/String;\)Ljava/lang/String;)')
    hits = 0
    for path, text in list(files.items()):
        if "/novel/player/video_model/v1/" not in text:
            continue
        new, n = pick_re.subn(lambda m: "\n" + m.group(2) + "invoke-static/range {" + m.group(4) + " .. " + m.group(4) + "}, " + HOOKS + "->pickLine(Ljava/lang/Object;)V\n" + m.group(1), text)
        if n:
            path.write_text(new, encoding="utf-8"); files[path] = new; hits += n
    report(hits == 1, "主线路 / 备用线路（backup_url_1）轮替" + ("" if hits == 1 else f"：解析 main_url 的位置找到 {hits} 个"))

    # 5. 认出内容仓库：所有 (String, int) -> StreamInfo 的非静态方法（取地址 / 读快取地址）最前面
    stream_cls = find_class_by_string(files, "StreamInfo(url=")
    caps = method_bodies(files, lambda p, x, m: "static" not in m.group(1).split() and m.group(3).startswith("Ljava/lang/String;I") and m.group(4) == stream_cls)
    for path, m in sorted(caps, key=lambda c: -c[1].start()):
        fresh = [mm for mm in METHOD_RE.finditer(files[path]) if mm.start() == m.start()]
        if fresh:
            insert_at_method_start(files, path, fresh[0], "invoke-static/range {p0 .. p0}, " + HOOKS + "->repoCapture(Ljava/lang/Object;)V")
    report(len(caps) > 0, f"失败时清记忆体里快取的播放地址（{len(caps)} 个入口）" if caps else "失败时清快取地址：找不到取地址的方法")


def tune_warm(files):
    """下一集暖机：作者在剩 30 秒时才开始，失败每 10 秒重试、最多 5 次。
    改成剩 75 秒（一集约 90 秒，等于开播不久就准备），失败每 4 秒重试、最多 7 次（每次重取地址会轮替主 / 备线路）。"""
    marker = "0x46ea6000    # 30000.0f"
    hits = method_bodies(files, lambda p, x, m: marker in m.group(5) and "isActiveNetworkMetered" in m.group(5))
    ok = False
    if len(hits) == 1:
        path, m = hits[0]
        body = m.group(5)
        nb, a = re.subn(r"const (v\d+), 0x46ea6000    # 30000\.0f", r"const \1, 0x47927c00    # 75000.0f", body)
        nb, b = re.subn(r"const-wide/16 (v\d+), 0x2710\n", r"const-wide/16 \1, 0xfa0\n", nb)
        nb, c = re.subn(r"const/4 (v\d+), 0x5(\s+if-ge v\d+, \1,)", r"const/4 \1, 0x7\2", nb)
        if a == 1 and b == 1 and c == 1:
            text = files[path]
            text = text[:m.start(5)] + nb + text[m.end(5):]
            path.write_text(text, encoding="utf-8"); files[path] = text
            ok = True
    report(ok, "下一集提早暖机（剩 75 秒开始，失败每 4 秒重试、最多 7 次）" + ("" if ok else "：找不到作者的暖机条件"))

def limit_prepare(files, reader_path):
    """前景起播限 16 秒：只在「起播准备」方法（会丢 PlaybackPrepareException 的那个）呼叫读取器 prepare 的地方前后挂
    Hooks.prepareBegin / prepareEnd。背景暖机（同一个读取器 prepare 方法被别处呼叫）不受限制。"""
    rcls = class_name(files[reader_path])
    hits = [m for m in METHOD_RE.finditer(files[reader_path]) if "static" not in m.group(1).split() and m.group(3) == "" and m.group(4) == "V" and '"prepared"' in m.group(5)]
    ok = False
    detail = f"：读取器 prepare 方法找到 {len(hits)} 个"
    if len(hits) == 1:
        pname = hits[0].group(2)
        sites = method_bodies(files, lambda p, x, m: "PlaybackPrepareException;-><init>" in m.group(5) and "static" not in m.group(1).split())
        detail = f"：起播准备方法找到 {len(sites)} 个"
        if len(sites) == 1:
            path, m = sites[0]
            pat = re.compile(r"\n(\s*)invoke-virtual \{(v\d+)\}, " + re.escape(rcls) + "->" + re.escape(pname) + r"\(\)V\n")
            found = pat.findall(m.group(5))
            detail = f"：前景呼叫点找到 {len(found)} 个"
            if len(found) == 1:
                def rep(mm):
                    ind, r = mm.group(1), mm.group(2)
                    return ("\n" + ind + "invoke-static/range {" + r + " .. " + r + "}, " + HOOKS + "->prepareBegin(Ljava/lang/Object;)V\n"
                            + "\n" + ind + "invoke-virtual {" + r + "}, " + rcls + "->" + pname + "()V\n"
                            + "\n" + ind + "invoke-static/range {" + r + " .. " + r + "}, " + HOOKS + "->prepareEnd(Ljava/lang/Object;)V\n")
                nb = pat.sub(rep, m.group(5), count=1)
                text = files[path]
                text = text[:m.start(5)] + nb + text[m.end(5):]
                path.write_text(text, encoding="utf-8"); files[path] = text
                ok = True
    report(ok, "前景起播限 16 秒（背景暖机不限）" + ("" if ok else detail))

def tune_pool(files):
    """闲置连线只留 15 秒（OkHttp 默认 5 分钟）。
    缓冲加大后整集很早就载完，连线闲置一两分钟会被 CDN / 路由默默断掉；下一集再拿这条死连线就卡到逾时。
    实测：切集第一下常卡满 25 秒，清掉连线池后立刻就好。这里让闲置连线 15 秒就关，下一集一定开新连线。"""
    hits = method_bodies(files, lambda p, x, m: m.group(2) == "<init>" and '" ConnectionPool"' in m.group(5) and "TimeUnit;->MINUTES" in m.group(5))
    if not hits:   # 1.3.x：OkHttp 换版本，连线池建构子只剩 MINUTES + 5 + toNanos
        hits = method_bodies(files, lambda p, x, m: m.group(2) == "<init>" and m.group(3) == "I" and "TimeUnit;->MINUTES" in m.group(5)
                             and "->toNanos(J)J" in m.group(5) and re.search(r"const-wide/16 v\d+, 0x5\n", m.group(5)) and "Ljava/util/ArrayDeque;" in m.group(5))
    ok = False
    if len(hits) == 1:
        path, m = hits[0]
        body = m.group(5)
        nb, a = re.subn(r"sget-object (v\d+), Ljava/util/concurrent/TimeUnit;->MINUTES:Ljava/util/concurrent/TimeUnit;", r"sget-object \1, Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;", body)
        nb, b = re.subn(r"const-wide/16 (v\d+), 0x5\n", r"const-wide/16 \1, 0xf\n", nb)
        if a == 1 and b == 1:
            text = files[path]
            text = text[:m.start(5)] + nb + text[m.end(5):]
            path.write_text(text, encoding="utf-8"); files[path] = text
            ok = True
    report(ok, "闲置连线只留 15 秒（切集不再拿到被断掉的旧连线）" + ("" if ok else "：找不到连线池设定"))

def hook_play_start(files, reader_path):
    """起播准备一拿到集数 ID 就呼叫 Hooks.playStarting(id, 背景读取器)：
    地址放超过 5 分钟就丢掉快取地址 + 关掉背景读取器，回传 true 时把读取器变量设成 null，让作者重新取地址；
    同时记下目前这一集，失败时只清这一集。"""
    rcls = class_name(files[reader_path])
    hits = method_bodies(files, lambda p, x, m: "PlaybackPrepareException;-><init>" in m.group(5) and "static" not in m.group(1).split())
    ok = False
    detail = f"：起播准备方法找到 {len(hits)} 个"
    if len(hits) == 1:
        path, m = hits[0]
        body = m.group(5)
        rd = re.search(r"\n\s*iget-object (v\d+), p0, L[^;]+;->\w+:" + re.escape(rcls) + r"\n", body)
        sid = re.search(r"\n(\s*)iget-object (v\d+), p0, L[^;]+;->\w+:Ljava/lang/String;\n", body)
        loc = re.search(r"^\s*\.locals (\d+)", body, re.M)
        detail = "：找不到读取器 / 集数 ID / .locals"
        if rd and sid and loc and rd.start() < sid.start():
            n = int(loc.group(1))
            if n + 1 <= 15:
                rv, ind, iv, nv = rd.group(1), sid.group(1), sid.group(2), "v" + str(n)
                ins = (sid.group(0) + "\n" + ind + "invoke-static {" + iv + ", " + rv + "}, " + HOOKS + "->playStarting(Ljava/lang/String;Ljava/lang/Object;)Z\n"
                       + "\n" + ind + "move-result " + nv + "\n"
                       + "\n" + ind + "if-eqz " + nv + ", :hgtv_keep_reader\n"
                       + "\n" + ind + "const/4 " + rv + ", 0x0\n"
                       + "\n" + ind + ":hgtv_keep_reader\n")
                nb = body[:sid.start()] + ins + body[sid.end():]
                nb = re.sub(r"^(\s*)\.locals \d+", lambda mm: mm.group(1) + ".locals " + str(n + 1), nb, count=1, flags=re.M)
                text = files[path]
                text = text[:m.start(5)] + nb + text[m.end(5):]
                path.write_text(text, encoding="utf-8"); files[path] = text
                ok = True
            else:
                detail = "：暂存器不够"
    report(ok, "起播前地址放超过 5 分钟才换新的；失败时只清「这一集」的快取" + ("" if ok else detail))


def auto_retry_forever(files):
    """不用手动按重试：
    1) 作者「已自动重试 3 次」的计数器每次检查前压回 2，永远不会走到「按确定键重试」画面；
    2) 作者重取播放地址的上限 4 次 → 30 次；
    3) 自动重试的等待 3 / 6 / 12 秒 → 一律 2 秒（计数压在 2 之后原本会每次都等 12 秒）。"""
    ok1 = ok2 = False
    hits = method_bodies(files, lambda p, x, m: "\\u5df2\\u81ea\\u52a8\\u91cd\\u8bd5 3 \\u6b21" in m.group(5))
    if len(hits) == 1:
        path, m = hits[0]
        # 作者：iget-object vA, vS, F / iget-object vB, vS, F / iget vA, vA, Z->b:I —— vA 被覆盖，物件要用 vB
        pat = re.compile(r"\n(\s*)iget-object (v\d+), (v\d+), (\S+)\n(\s*\n\s*)iget-object (v\d+), \3, \4\n(\s*\n\s*)iget \2, \2, (L[^;]+;->\w+:I)\n(\s*\n\s*const/4 v\d+, 0x0\n\s*\n\s*const/4 (v\d+), 0x3\n)")
        found = pat.findall(m.group(5))
        if len(found) == 1:
            def rep(mm):
                ind, ra, rs, f, s1, rb, s2, cnt, rest, c3 = mm.groups()
                return ("\n" + ind + "iget-object " + ra + ", " + rs + ", " + f + "\n" + s1 + "iget-object " + rb + ", " + rs + ", " + f + "\n" + s2
                        + "iget " + ra + ", " + ra + ", " + cnt + "\n"
                        + "\n" + ind + "const/4 " + c3 + ", 0x2\n"
                        + "\n" + ind + "if-le " + ra + ", " + c3 + ", :hgtv_retry_ok\n"
                        + "\n" + ind + "iput " + c3 + ", " + rb + ", " + cnt + "\n"
                        + "\n" + ind + "move " + ra + ", " + c3 + "\n"
                        + "\n" + ind + ":hgtv_retry_ok\n" + rest)
            nb = pat.sub(rep, m.group(5), count=1)
            nb, nd = re.subn(r"(const-wide/16 v\d+, )0x(bb8|1770|2ee0)\n", r"\g<1>0x7d0\n", nb)
            if nd == 3:
                text = files[path]
                text = text[:m.start(5)] + nb + text[m.end(5):]
                path.write_text(text, encoding="utf-8"); files[path] = text
                ok1 = True
    if not ok1:
        ok1 = _auto_retry_13(files)
    hits = method_bodies(files, lambda p, x, m: "Transport dead" in m.group(5) and "\\u6309\\u786e\\u5b9a\\u952e\\u91cd\\u8bd5" in m.group(5))
    if len(hits) == 1:
        path, m = hits[0]
        nb, n = re.subn(r"(\n\s*iput (v\d+), (v\d+), Lcom/hgtv/star/MainActivity;->\w+:I\n\s*\n\s*)const/4 (v\d+), 0x4(\n\s*\n\s*if-le \2, \4, :cond_\w+\n)", r"\1const/16 \4, 0x1e\5", m.group(5))
        if n == 1:
            text = files[path]
            text = text[:m.start(5)] + nb + text[m.end(5):]
            path.write_text(text, encoding="utf-8"); files[path] = text
            ok2 = True
    if not ok2:
        ok2 = _giveup_limit_13(files)
    report(ok1 and ok2, "自动重试不停（不用手动按重试；每次等 2 秒；重取地址上限 4 → 30 次）" + ("" if ok1 and ok2 else f"：自动重试计数 {'✓' if ok1 else '✗'}、重取上限 {'✓' if ok2 else '✗'}"))



def _auto_retry_13(files):
    """1.3.x：自动重试计数在「网络已断开…」那个方法里（iget-object 计数物件 / iget 计数 / const 3），等待秒数另外放在一个回传 Long 的方法。"""
    net_msg = "\\u7f51\\u7edc\\u5df2\\u65ad\\u5f00\\uff0c\\u8054\\u7f51\\u540e\\u81ea\\u52a8\\u7eed\\u64ad"
    hits = method_bodies(files, lambda p, x, m: net_msg in m.group(5))
    ok_clamp = False
    if len(hits) == 1:
        path, m = hits[0]
        pat = re.compile(r"\n(\s*)iget-object (v\d+), (v\d+), (L[^;]+;)->(\w+):(L[^;]+;)\n(\s*\n\s*)iget \2, \2, \6->(\w+):I\n(\s*\n\s*)const/4 (v\d+), 0x3\n")
        found = pat.findall(m.group(5))
        if len(found) == 1:
            def rep(mm):
                ind, a, s, cls, f, ocls, s1, cnt, s2, c = mm.groups()
                field = ocls + "->" + cnt + ":I"
                return ("\n" + ind + "iget-object " + a + ", " + s + ", " + cls + "->" + f + ":" + ocls + "\n"
                        + "\n" + ind + "iget " + c + ", " + a + ", " + field + "\n"
                        + "\n" + ind + "add-int/lit8 " + c + ", " + c + ", -0x3\n"
                        + "\n" + ind + "if-ltz " + c + ", :hgtv_retry_ok\n"
                        + "\n" + ind + "const/4 " + c + ", 0x2\n"
                        + "\n" + ind + "iput " + c + ", " + a + ", " + field + "\n"
                        + "\n" + ind + ":hgtv_retry_ok\n"
                        + s1 + "iget " + a + ", " + a + ", " + field + "\n" + s2 + "const/4 " + c + ", 0x3\n")
            nb = pat.sub(rep, m.group(5), count=1)
            text = files[path]
            text = text[:m.start(5)] + nb + text[m.end(5):]
            path.write_text(text, encoding="utf-8"); files[path] = text
            ok_clamp = True
    dl = method_bodies(files, lambda p, x, m: m.group(4) == "Ljava/lang/Long;" and m.group(3) == "" and "[Ljava/lang/Long;" in m.group(5)
                       and all(re.search(r"const-wide/16 v\d+, 0x" + v + r"\n", m.group(5)) for v in ("bb8", "1770", "2ee0")))
    ok_delay = False
    if len(dl) == 1:
        path, m = dl[0]
        nb, nd = re.subn(r"(const-wide/16 v\d+, )0x(bb8|1770|2ee0)\n", r"\g<1>0x7d0\n", m.group(5))
        if nd == 3:
            text = files[path]
            text = text[:m.start(5)] + nb + text[m.end(5):]
            path.write_text(text, encoding="utf-8"); files[path] = text
            ok_delay = True
    return ok_clamp and ok_delay


def _giveup_limit_13(files):
    """1.3.x：放弃（GiveUpRecommend）的门槛在重试政策里：计数 +1 后 > 3 就放弃 → 改成 > 30。"""
    gp = [p for p, x in files.items() if '"GiveUpRecommend"' in x]
    if len(gp) != 1:
        return False
    gcls = class_name(files[gp[0]])
    pat = re.compile(r"const/4 (\w+), 0x3(\n\s*\n\s*if-le \w+, \1, :cond_\w+\n\s*\n\s*sget-object \1, " + re.escape(gcls) + r"->)")
    n_total = 0
    for path, text in list(files.items()):
        new, n = pat.subn(r"const/16 \1, 0x1e\2", text)
        if n:
            path.write_text(new, encoding="utf-8"); files[path] = new; n_total += n
    return n_total == 1


def hook_seamless(files, reader_path):
    """诊断用：作者「预载好的下一集直接接上播」那一处（下一集读取器 → 目前读取器 → 上一个读取器 连续搬移）挂 Hooks.seamlessSwitch，
    诊断页摘要才算得到「预载直接播」的换集。"""
    rcls = class_name(files[reader_path])
    pair = re.compile(r"\n(\s*)iget-object (v\d+), (v\d+), (L[^;]+;)->(\w+):" + re.escape(rcls) + r"\n\s*\n\s*iput-object \2, \3, \4->(\w+):" + re.escape(rcls) + r"\n")
    hits = []
    for path, text in files.items():
        for m in METHOD_RE.finditer(text):
            body = m.group(5)
            ps = list(pair.finditer(body))
            if len(ps) < 2: continue
            srcs = {x.group(5) for x in ps}
            for x in ps:
                if x.group(6) in srcs and x.group(6) != x.group(5):
                    hits.append((path, m, x))
    ok = len(hits) == 1
    if ok:
        path, m, x = hits[0]
        body = m.group(5)
        nb = body[:x.end()] + "\n" + x.group(1) + "invoke-static {}, " + HOOKS + "->seamlessSwitch()V\n" + body[x.end():]
        text = files[path]
        text = text[:m.start(5)] + nb + text[m.end(5):]
        path.write_text(text, encoding="utf-8"); files[path] = text
    report(ok, "诊断：算得到「预载直接播」的换集" + ("" if ok else f"：找到 {len(hits)} 处"))

def no_quality_down(files):
    """不自动降画质：作者的「降档」预算（QUALITY_DOWN，原本 2 次）改成 0 次；同画质重建（SAME_QUALITY_REBUILD）照旧。"""
    enum = [p for p, x in files.items() if '"QUALITY_DOWN"' in x and '"SAME_QUALITY_REBUILD"' in x]
    ok = False
    if len(enum) == 1:
        ecls = class_name(files[enum[0]])
        hits = method_bodies(files, lambda p, x, m: m.group(4) == "Z" and m.group(3).startswith(ecls) and "static" not in m.group(1).split() and re.search(r"const/4 p1, 0x2\n", m.group(5)))
        if len(hits) == 1:
            path, m = hits[0]
            nb, n = re.subn(r"const/4 p1, 0x2\n", "const/4 p1, 0x0\n", m.group(5))
            if n == 1:
                text = files[path]
                text = text[:m.start(5)] + nb + text[m.end(5):]
                path.write_text(text, encoding="utf-8"); files[path] = text
                ok = True
    report(ok, "不自动降画质（降档预算 2 → 0，出错改用同画质重建 / 换线路重试）" + ("" if ok else "：找不到降档预算"))


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

    # 1.3.x 起取地址的方法多了参数（例如 (String, int, long, 回调)），只要求开头是 (String, int)
    stream = [s for s in find_methods_prefix(files, "Ljava/lang/String;I", stream_cls, '"spade_a"')]
    ranges = find_methods(files, "JJ", range_cls, '"https://novel.snssdk.com/"')
    details = find_methods(files, "Ljava/lang/String;", detail_cls)
    if len(stream) != 1 or stream[0][2]:
        die(f"取播放地址的方法应恰好 1 个且非静态，实际：{[(s[1], s[2]) for s in stream]}")
    if len(ranges) != 1 or ranges[0][2]:
        die(f"分段读取的方法应恰好 1 个且非静态，实际：{[(r[1], r[2]) for r in ranges]}")
    if not details:
        die("找不到取详情的方法")
    print(f"stream={stream[0][0].name}->{stream[0][1]} range={ranges[0][0].name}->{ranges[0][1]} detail={[(d[0].name, d[1]) for d in details]}")

    # 预载模块要自己 new 一个「读取影片」的对象来下载：它的构造参数里必须有 StreamInfo，
    # 其余只能是一个连接对象加上字串 / 基本型别（见 Hooks.learnVideo），否则这个版本不支持。
    video_text = files[ranges[0][0]]
    ctors = re.findall(r"^\.method [^\n]*constructor <init>\(([^)]*)\)V", video_text, re.M)
    def ctor_ok(desc):
        parts = re.findall(r"\[*(?:L[^;]+;|[ZBCSIJFD])", desc)
        objects = [x for x in parts if x.startswith("L") and x not in (stream_cls, "Ljava/lang/String;")]
        return stream_cls in parts and len(objects) == 1
    if not any(ctor_ok(c) for c in ctors):
        die(f"读取影片的类没有可用的构造方法：{ctors}")
    print(f"video constructors={ctors}")

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

    # 1.3.x：取地址方法多了 (long, 物件) 参数 → 先记下来，当场重取时照原样带回去
    sp, sname = stream[0][0], stream[0][1]
    sm = [mm for mm in METHOD_RE.finditer(files[sp]) if mm.group(2) == sname and mm.group(3).startswith("Ljava/lang/String;I") and mm.group(4) == stream_cls]
    if len(sm) == 1 and re.fullmatch(r"Ljava/lang/String;IJL[^;]+;", sm[0].group(3)):
        insert_at_method_start(files, sp, sm[0], "invoke-static/range {p3 .. p5}, " + HOOKS + "->streamExtra(JLjava/lang/Object;)V")
        print("stream extra args captured (J,L)")
    elif len(sm) == 1 and re.fullmatch(r"Ljava/lang/String;IJL[^;]+;L[^;]+;", sm[0].group(3)):   # 1.3.5：再多一个 List
        insert_at_method_start(files, sp, sm[0], "invoke-static/range {p3 .. p6}, " + HOOKS + "->streamExtra(JLjava/lang/Object;Ljava/lang/Object;)V")
        print("stream extra args captured (J,L,L)")
    elif len(sm) == 1 and sm[0].group(3) != "Ljava/lang/String;I":
        report(False, "当场重取：取地址方法参数是 " + sm[0].group(3) + "，没认出来")
    tune_stall_recovery(files)
    tune_buffer(files)
    add_fix_hooks(files)
    tune_warm(files)
    limit_prepare(files, ranges[0][0])
    tune_pool(files)
    hook_play_start(files, ranges[0][0])
    auto_retry_forever(files)
    hook_seamless(files, ranges[0][0])
    no_quality_down(files)

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
    # androidx 自动宣告的「包名.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION」也要跟着改名，
    # 否则和作者版装在同一台时会 INSTALL_FAILED_DUPLICATE_PERMISSION（执行时 androidx 本来就用新包名拼这个权限）。
    mtext, n = re.subn(r'com\.hgtv\.star\.([A-Z0-9_]+_PERMISSION)', lambda m: f"{args.package}.{m.group(1)}", mtext)
    report(True, f"自订权限改名（{n} 处），可与作者版并存")
    manifest.write_text(mtext, encoding="utf-8")
    pathlib.Path("patch-report.md").write_text("\n".join("- " + r for r in REPORT) + "\n", encoding="utf-8")
    print("\n".join(REPORT))
    print("done")


if __name__ == "__main__":
    main()
