#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
端到端冒烟测试：一条命令把整条业务链路走一遍。

    python3 scripts/smoke.py
    python3 scripts/smoke.py --base http://192.168.1.20:8080/api --password admin123

覆盖的链路：
    登录 → 找批次 → 建检验单（按标准快照展开）→ 按限值自动填合格值
    → 提交判定 → 审核归档 → 查操作日志 → 导出台账 CSV

为什么要这个脚本：
    单测只能证明判定逻辑是对的，证明不了「建单能建出来、字段能落库、
    快照没丢、权限没拦错人」。这些只有真连数据库跑一遍才知道。

依赖：Python 3.8+ 标准库，不需要 pip install。
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

OK = "\033[32m  OK \033[0m"
FAIL = "\033[31m FAIL\033[0m"


class SmokeFailure(Exception):
    pass


class Api:
    def __init__(self, base, token=None):
        self.base = base.rstrip("/")
        self.token = token

    def call(self, method, path, body=None, raw=False):
        url = self.base + path
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        if self.token:
            headers["Authorization"] = "Bearer " + self.token

        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                payload = resp.read()
                if raw:
                    return resp.status, payload
                return json.loads(payload.decode("utf-8"))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            raise SmokeFailure("%s %s -> HTTP %s %s" % (method, path, e.code, detail[:300]))
        except urllib.error.URLError as e:
            raise SmokeFailure("连不上 %s：%s（服务起来了吗？）" % (url, e.reason))

    def data(self, method, path, body=None):
        r = self.call(method, path, body)
        if r.get("code") != 0:
            raise SmokeFailure("%s %s 返回业务错误：%s" % (method, path, r.get("msg")))
        return r.get("data")


def step(name):
    print("\n▶ " + name)


def ok(msg):
    print(OK + " " + msg)


# ---------------- 造合格值 ----------------

def number_value(line):
    lo = line.get("minValue")
    hi = line.get("maxValue")
    lo = None if lo is None else float(lo)
    hi = None if hi is None else float(hi)
    if lo is not None and hi is not None:
        v = (lo + hi) / 2
    elif lo is not None:
        v = lo
    elif hi is not None:
        v = hi
    else:
        v = 1.0
    return ("%f" % v).rstrip("0").rstrip(".") or "0"


def enum_value(line):
    raw = line.get("optionsJson")
    options = []
    if raw:
        try:
            options = json.loads(raw) if isinstance(raw, str) else raw
        except (ValueError, TypeError):
            options = []
    if not options:
        # 标准项没覆盖选项时，快照里可能为空，此时期望接口给的是主数据选项；
        # 拿不到就直接报出来，而不是瞎填一个值让判定结论不可信
        raise SmokeFailure("枚举项「%s」没有可用的选项快照" % line.get("itemName"))
    for o in options:
        if o.get("pass") is True:
            return o.get("code")
    return options[0].get("code")


def build_lines(detail):
    """按标准限值自动生成一组「应该判合格」的实测值。"""
    lines = []
    for piece in detail.get("pieces", []):
        for group in (piece.get("groups") or {}).values():
            for line in group:
                vtype = line.get("valueType")
                count = max(int(line.get("pointCount") or 1), 1)
                item = {"lineId": line.get("id"), "textValue": None, "gaugeId": None}

                if vtype == "TEXT":
                    item["values"] = []
                    item["textValue"] = "冒烟测试"
                elif vtype == "BOOL":
                    item["values"] = ["true" if line.get("boolPassValue") else "false"]
                elif vtype == "ENUM":
                    item["values"] = [enum_value(line)] * count
                else:
                    item["values"] = [number_value(line)] * count
                lines.append(item)
    return lines


def pick_batch(api):
    batches = api.data("GET", "/batches")
    for b in batches:
        if b.get("status") in ("PENDING", "INSPECTING"):
            return b
    if batches:
        return batches[0]

    # 一条批次都没有：自己建一个，保证脚本在任何空库上都能跑
    products = api.data("GET", "/products")
    if not products:
        raise SmokeFailure("库里既没有批次也没有产品，V1 初始化数据没跑成功")
    p = products[0]
    batch = api.data("POST", "/batches", {
        "productId": p.get("id"),
        "batchNo": "SMOKE-" + time.strftime("%Y%m%d%H%M%S"),
        "qty": 5,
        "status": "PENDING",
    })
    return batch


def main():
    ap = argparse.ArgumentParser(description="质检系统端到端冒烟测试")
    ap.add_argument("--base", default="http://localhost:8080/api", help="接口根地址")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin123")
    args = ap.parse_args()

    t0 = time.time()
    api = Api(args.base)

    step("1/8 登录")
    login = api.data("POST", "/auth/login", {"username": args.user, "password": args.password})
    api.token = login["token"]
    ok("登录成功：%s（%s）" % (login["user"].get("realName"), login["user"].get("role")))

    step("2/8 当前用户")
    me = api.data("GET", "/auth/me")
    ok("token 可解析：%s" % me.get("username"))

    step("3/8 找一条可检验的批次")
    batch = pick_batch(api)
    ok("批次 %s（%s 件，状态 %s）" % (batch.get("batchNo"), batch.get("qty"), batch.get("status")))

    step("4/8 建检验单（按生效标准快照展开）")
    created = api.data("POST", "/inspections", {"batchId": batch.get("id")})
    inspection_id = created["inspectionId"]
    ok("检验单 %s，展开 %s 行，允收数 Ac=%s"
       % (created.get("inspectionNo"), created.get("lineCount"), created.get("acceptQty")))

    step("5/8 按限值自动填实测值并提交判定")
    detail = api.data("GET", "/inspections/%s" % inspection_id)
    lines = build_lines(detail)
    if not lines:
        raise SmokeFailure("检验单没有任何明细行，标准可能是空的")
    outcome = api.data("POST", "/inspections/%s/submit" % inspection_id, {"lines": lines})
    if not outcome.get("pass"):
        raise SmokeFailure("按限值填的合格值却判了不合格：%s" % outcome.get("reason"))
    ok("判定通过：%s" % outcome.get("reason"))

    step("6/8 提交后必须锁死")
    detail = api.data("GET", "/inspections/%s" % inspection_id)
    if detail.get("locked") is not True:
        raise SmokeFailure("已提交的单据没有被锁定，任何人都能改，质量记录没有证据价值")
    ok("locked = true，单据已锁定")

    step("7/8 审核归档")
    api.call("POST", "/inspections/%s/review" % inspection_id,
             {"approve": True, "comment": "冒烟测试自动审核"})
    detail = api.data("GET", "/inspections/%s" % inspection_id)
    status = detail["inspection"]["status"]
    if status != "CLOSED":
        raise SmokeFailure("审核通过后状态应为 CLOSED，实际 %s" % status)
    ok("状态 CLOSED，审核人 %s" % detail["inspection"].get("reviewedBy"))

    logs = api.data("GET", "/inspections/%s/logs" % inspection_id)
    actions = [l.get("action") for l in logs]
    for need in ("CREATE", "SUBMIT", "REVIEW"):
        if need not in actions:
            raise SmokeFailure("操作日志缺少 %s，留痕不完整：%s" % (need, actions))
    ok("操作留痕完整：%s" % " → ".join(actions))

    step("8/8 导出台账 CSV")
    status_code, payload = api.call("GET", "/export/inspections", raw=True)
    if status_code != 200:
        raise SmokeFailure("导出返回 HTTP %s" % status_code)
    if not payload.startswith(b"\xef\xbb\xbf"):
        raise SmokeFailure("导出没有 UTF-8 BOM，Excel 打开会中文乱码")
    ok("导出 %d 字节，带 BOM" % len(payload))

    print("\n\033[32m全部通过\033[0m  （耗时 %.1fs）" % (time.time() - t0))
    print("检验单：%s" % created.get("inspectionNo"))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SmokeFailure as e:
        print("\n" + FAIL + " " + str(e))
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)
