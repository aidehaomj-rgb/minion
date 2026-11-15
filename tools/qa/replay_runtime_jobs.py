#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Offline replay of explicitly selected, sanitized saved metrics/events (Python 3.7+).
Never executes tools, SQL or model calls. No semantic gold => unknown, not zero/perfect.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path

UNKNOWN = "unknown_not_labelled"
FLAGS = ("latestInstructionFollowed", "prematureStop", "crossProjectLeak")


def number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


def replay(report):
    """Fold recorded events in given order; eventId dedup is not execution dedup."""
    events = report.get("events")
    trace = {"status": "unknown_no_event_trace"}
    if events is not None:
        if not isinstance(events, list):
            raise ValueError("events must be an array")
        seen, executions, pending = {}, set(), set()
        trace = {"status": "observed", "events": 0, "duplicateEventsIgnored": 0,
                 "repeatedExecutions": 0, "staleRevisionExecutions": 0,
                 "stopsWithPendingExecutions": 0, "latestRequirementRevision": None}
        revision = None
        for event in events:
            if not isinstance(event, dict) or not isinstance(event.get("eventId"), str):
                raise ValueError("trace event requires string eventId")
            eid, fingerprint = event["eventId"], digest(event)
            if eid in seen:
                if seen[eid] != fingerprint:
                    raise ValueError("conflicting duplicate eventId")
                trace["duplicateEventsIgnored"] += 1
                continue
            seen[eid] = fingerprint
            trace["events"] += 1
            kind = event.get("kind")
            if kind == "requirement":
                new = event.get("revision")
                if type(new) is not int or new < 1 or (revision is not None and new < revision):
                    raise ValueError("requirement revisions must be monotonic positive integers")
                revision = new
                trace["latestRequirementRevision"] = new
            elif kind == "execution_started":
                key = event.get("executionKey")
                instance = event.get("instanceId")
                if not isinstance(key, str) or not isinstance(instance, str):
                    raise ValueError("execution requires executionKey and instanceId")
                trace["repeatedExecutions"] += int(key in executions)
                executions.add(key)
                pending.add(instance)
                if revision is not None:
                    if type(event.get("revision")) is not int:
                        raise ValueError("execution after requirement requires revision")
                    trace["staleRevisionExecutions"] += int(event["revision"] != revision)
            elif kind == "execution_finished":
                pending.discard(event.get("instanceId"))
            elif kind == "stop":
                trace["stopsWithPendingExecutions"] += int(bool(pending))
        trace["pendingExecutionsAtEnd"] = len(pending)
        trace["caution"] = "Repeated executions/pending stops are observations, not error labels; no content-based leak inference."
    review = report.get("manualBusinessReview", {})
    if not isinstance(review, dict):
        review = {}
    counts = [review.get(k) for k in ("expectedItems", "omittedItems", "duplicateItems", "incorrectItems")]
    valid = (type(review.get("accepted")) is bool and
             all(type(v) is int and v >= 0 for v in counts) and
             counts[1] <= counts[0] and counts[3] <= counts[0])
    if review.get("taskId") is not None and review["taskId"] != report.get("taskId"):
        valid = False
    quality = {k: review[k] if valid and type(review.get(k)) is bool else UNKNOWN for k in FLAGS}
    quality["accepted"] = review["accepted"] if valid else UNKNOWN
    quality["falseCompletion"] = (not review["accepted"] if valid and report.get("status") == "completed" else UNKNOWN)
    quality["businessAccuracy"] = "unknown_without_item_level_gold"
    quality["coverage"] = ((counts[0] - counts[1]) / counts[0] if valid and counts[0] else UNKNOWN)
    usage = {}
    # Existing TaskEvaluation format: NEVER substitute session totals for task deltas.
    for k in ("taskInputTokens", "taskOutputTokens", "activeMillis", "duplicateToolArguments"):
        value = report.get(k)
        usage[k] = value if number(value) else "unknown_missing_or_invalid"
    usage["tokenScope"] = report.get("taskTokenScope", "unknown_scope")
    return {"quality": quality, "metrics": usage, "trace": trace}


def identity(report):
    key = report.get("caseId", report.get("taskId"))
    if not isinstance(key, str) or not key:
        raise ValueError("caseId or taskId required; never match runs by array position")
    # Unknown revision/attempt stays explicit; no inference from goal text.
    return json.dumps([key, report.get("workflowRevision", report.get("revision")),
                       report.get("attempt")], ensure_ascii=False)


def index(reports):
    out, seen, duplicates = {}, {}, 0
    for report in reports:
        if not isinstance(report, dict):
            raise ValueError("each run must be an object")
        key, fingerprint = identity(report), digest(report)
        if key in seen:
            if seen[key] != fingerprint:
                raise ValueError("multiple differing runs for same case/revision/attempt; select one explicitly")
            duplicates += 1
            continue
        seen[key] = fingerprint
        out[key] = replay(report)
    return out, duplicates


def compare(before, after):
    left, ld = index(before)
    right, rd = index(after)
    pairs = []
    for key in sorted(set(left) & set(right)):
        a, b = left[key], right[key]
        delta = {}
        for metric in ("taskInputTokens", "taskOutputTokens", "activeMillis", "duplicateToolArguments"):
            av, bv = a["metrics"][metric], b["metrics"][metric]
            compatible = (metric not in ("taskInputTokens", "taskOutputTokens") or
                          a["metrics"]["tokenScope"] == b["metrics"]["tokenScope"] != "unknown_scope")
            delta[metric] = bv - av if number(av) and number(bv) and compatible else "unknown_unpaired_or_scope"
        for metric in ("repeatedExecutions", "staleRevisionExecutions", "stopsWithPendingExecutions"):
            av, bv = a["trace"].get(metric), b["trace"].get(metric)
            delta[metric] = bv - av if number(av) and number(bv) else "unknown_no_paired_trace"
        pairs.append({"identity": json.loads(key), "before": a, "after": b, "deltaAfterMinusBefore": delta})
    totals = {}
    for flag in FLAGS + ("falseCompletion", "accepted"):
        totals[flag] = {}
        for side, runs in (("before", left), ("after", right)):
            labelled = [r["quality"][flag] for r in runs.values() if type(r["quality"][flag]) is bool]
            totals[flag][side] = {"labelled": len(labelled), "unknown": len(runs)-len(labelled),
                                  "trueRate": sum(labelled)/len(labelled) if labelled else UNKNOWN}
    paired_deltas = {}
    for metric in ("taskInputTokens", "taskOutputTokens", "activeMillis", "duplicateToolArguments",
                   "repeatedExecutions", "staleRevisionExecutions", "stopsWithPendingExecutions"):
        values = [p["deltaAfterMinusBefore"][metric] for p in pairs
                  if type(p["deltaAfterMinusBefore"][metric]) in (int, float)]
        paired_deltas[metric] = {"evidencePairs": len(values), "unknownPairs": len(pairs)-len(values),
                                 "meanAfterMinusBefore": sum(values)/len(values) if values else "unknown_no_paired_evidence"}
    return {"mode": "offline_saved_evidence_replay_not_live_execution", "paired": len(pairs),
            "unmatchedBefore": sorted(set(left)-set(right)), "unmatchedAfter": sorted(set(right)-set(left)),
            "duplicateReportsIgnored": {"before": ld, "after": rd},
            "quality": totals, "pairedDeltas": paired_deltas, "pairs": pairs,
            "warning": "No business accuracy score without item-level gold. Repeated arguments are not necessarily duplicate effects. Unmatched runs excluded from deltas."}


def load(paths):
    reports = []
    for name in paths:
        path = Path(name)
        if not path.is_file() or path.stat().st_size > 10*1024*1024:
            raise ValueError("input missing or exceeds 10MB")
        with path.open(encoding="utf-8-sig") as stream:
            value = json.load(stream)
        rows = value.get("runs", [value]) if isinstance(value, dict) else value
        if not isinstance(rows, list):
            raise ValueError("expected metrics object, array, or runs wrapper")
        reports.extend(rows)
    return reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", nargs="+", required=True)
    parser.add_argument("--after", nargs="+", required=True)
    parser.add_argument("--output", help="exclusive-create output; never overwrite")
    args = parser.parse_args()
    try:
        result = compare(load(args.before), load(args.after))
        text = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)
        if args.output:
            with Path(args.output).open("x", encoding="utf-8") as stream:
                stream.write(text + "\n")
        print(text)
    except (ValueError, OSError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
