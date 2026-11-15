#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""离线汇总用户明确选中的任务评估JSON，兼容Python3.7；不读取模型配置或扫描会话。"""
import argparse
import json
from pathlib import Path


def summarize(reports):
    result = {"records": len(reports), "reviewed": 0, "accepted": 0,
              "claimed_completed_reviewed": 0, "false_completions": 0,
              "unreviewed": 0, "invalid": 0, "duplicates_ignored": 0,
              "business_accuracy": "unknown_without_item_level_gold_labels"}
    seen = set()
    flags = {key: {"reviewed": 0, "true": 0} for key in
             ("prematureStop", "latestInstructionFollowed", "sourceCitationFound", "crossProjectLeak")}
    for report in reports:
        task = report.get("taskId")
        if task and task in seen:
            result["duplicates_ignored"] += 1
            continue
        if task:
            seen.add(task)
        review = report.get("manualBusinessReview")
        if not isinstance(review, dict):
            result["unreviewed"] += 1
            continue
        counts = [review.get(k) for k in ("expectedItems", "omittedItems", "duplicateItems", "incorrectItems")]
        if (not isinstance(review.get("accepted"), bool) or
                any(type(v) is not int or v < 0 for v in counts) or
                counts[1] > counts[0] or counts[3] > counts[0]):
            result["invalid"] += 1
            continue
        result["reviewed"] += 1
        result["accepted"] += int(review["accepted"])
        for key, counts in flags.items():
            if isinstance(review.get(key), bool):
                counts["reviewed"] += 1
                counts["true"] += int(review[key])
        if report.get("status") == "completed":
            result["claimed_completed_reviewed"] += 1
            result["false_completions"] += int(not review["accepted"])
    result["acceptance_rate"] = (result["accepted"] / result["reviewed"]
                                  if result["reviewed"] else "unknown_no_manual_review")
    denominator = result["claimed_completed_reviewed"]
    result["false_completion_rate"] = (result["false_completions"] / denominator
                                       if denominator else "unknown_no_reviewed_completion")
    for counts in flags.values():
        counts["true_rate"] = (counts["true"] / counts["reviewed"]
                               if counts["reviewed"] else "unknown_not_labelled")
    result["manual_flags"] = flags
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="+", help="手动选择已脱敏的任务评估JSON，不支持通配目录扫描")
    parser.add_argument("--output", help="可选输出JSON；已存在则拒绝覆盖")
    args = parser.parse_args()
    reports = []
    for name in args.reports:
        path = Path(name)
        if not path.is_file() or path.stat().st_size > 10 * 1024 * 1024:
            parser.error("报告不存在或超过10MB: " + path.name)
        with path.open("r", encoding="utf-8-sig") as stream:
            report = json.load(stream)
        if not isinstance(report, dict):
            parser.error("报告必须为JSON对象: " + path.name)
        reports.append(report)
    text = json.dumps(summarize(reports), ensure_ascii=False, indent=2)
    if args.output:
        with Path(args.output).open("x", encoding="utf-8") as stream:
            stream.write(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
