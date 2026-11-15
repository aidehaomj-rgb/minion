#!/usr/bin/env python3
"""Bounded, non-streaming chat/completions protocol probe (Python 3.7 stdlib).

Default: validate cases and print a dry-run; NEVER contacts a model implicitly.
Local example:
  python tools/qa/probe_model_protocol.py --cases tools/qa/model_protocol_cases.jsonl
Real gateway (user must supply their own endpoint/model and run explicitly):
  python tools/qa/probe_model_protocol.py --cases tools/qa/model_protocol_cases.jsonl \
    --execute --endpoint http://YOUR-GATEWAY/v1/chat/completions --model YOUR-MODEL \
    --output YOUR-REPORT.json
Optional authorization comes ONLY from MODEL_API_KEY (or --api-key-env NAME).
No automatic history, redirects, environment proxies, retries, tools or tool execution.
Synthetic contains/not_contains checks are STRING ASSERTIONS, not business acceptance,
long-task acceptance, memory/"never forget" scores or completion reliability scores.
Each actual DeepSeek/Qwen model AND gateway must be exercised by the user: dry-run
and mock HTTP tests cannot establish actual provider compatibility or latest behavior.
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

MAX_CASE_BYTES = 1024 * 1024
MAX_CASES = 100
MAX_RESPONSE_BYTES = 1024 * 1024
NOTICE = ("Non-streaming protocol observations only. contains/not_contains are string "
          "assertions, not business/long-task acceptance or memory/completion reliability "
          "scores. first_response_ms is time to first HTTP body byte, NOT SSE TTFT. "
          "Each real model and gateway requires an explicit user-run probe.")


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse normally echoes untrusted CLI values, which may include secrets.
        self.exit(2, "probe failed: invalid command-line arguments (use --help)\n")


def reject_constant(value):
    raise ValueError("non-finite JSON value")


def bounded_int(value):
    number = int(value)
    if not 1 <= number <= MAX_RESPONSE_BYTES:
        raise argparse.ArgumentTypeError("byte limit must be 1..1048576")
    return number


def timeout_value(value):
    number = float(value)
    if not 0 < number <= 120:
        raise argparse.ArgumentTypeError("timeout must be >0 and <=120 seconds")
    return number


def endpoint_url(value):
    try:
        p = urllib.parse.urlsplit(value)
        if (p.scheme not in ("http", "https") or not p.hostname or p.username is not None
                or p.password is not None or p.query or p.fragment or p.port == 0
                or any(ord(c) <= 32 or ord(c) == 127 for c in value)
                or "\\" in value or not p.path.endswith("/chat/completions")):
            raise ValueError()
        # Access .port to reject malformed/out-of-range port values.
        unused = p.port
        return value
    except ValueError:
        raise ValueError("endpoint must be an http(s) /chat/completions URL without userinfo/query/fragment")


def load_cases(path):
    with open(str(path), "rb") as source:
        data = source.read(MAX_CASE_BYTES + 1)
    if len(data) > MAX_CASE_BYTES:
        raise ValueError("cases exceeds 1 MiB")
    cases, identifiers = [], set()
    for line in data.decode("utf-8-sig").splitlines():
        if not line.strip():
            continue
        item = json.loads(line, parse_constant=reject_constant)
        if not isinstance(item, dict) or set(item) - {"id", "messages", "contains", "not_contains"}:
            raise ValueError("case allows only id/messages/contains/not_contains; no tool parameters")
        ident = item.get("id")
        if not isinstance(ident, str) or not ident or len(ident) > 128 or ident in identifiers:
            raise ValueError("case id must be unique, nonempty, <=128 characters")
        identifiers.add(ident)
        messages = item.get("messages")
        if not isinstance(messages, list) or not 1 <= len(messages) <= 100:
            raise ValueError("messages must contain 1..100 explicit messages")
        for msg in messages:
            if (not isinstance(msg, dict) or set(msg) != {"role", "content"}
                    or msg["role"] not in ("system", "user", "assistant")
                    or not isinstance(msg["content"], str)):
                raise ValueError("messages accept only role(system/user/assistant) and string content")
        for field in ("contains", "not_contains"):
            checks = item.get(field, [])
            if (not isinstance(checks, list) or len(checks) > 100
                    or any(not isinstance(s, str) or not s or len(s) > 4096 for s in checks)):
                raise ValueError("string assertions must be lists of 1..4096-character strings")
        cases.append(item)
        if len(cases) > MAX_CASES:
            raise ValueError("too many cases (maximum 100)")
    if not cases:
        raise ValueError("cases is empty")
    return cases


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def redact(text, secret, truncated=False):
    if not secret:
        return text
    # Also cover JSON-escaped secret values and an incomplete secret at a bounded tail.
    for value in set((secret, json.dumps(secret, ensure_ascii=True)[1:-1])):
        text = text.replace(value, "[REDACTED]")
        if truncated:
            for length in range(min(len(value) - 1, len(text)), 0, -1):
                if text.endswith(value[:length]):
                    text = text[:-length] + "[REDACTED_PARTIAL]"
                    break
    return text


def usage_fields(parsed):
    usage = parsed.get("usage") if isinstance(parsed, dict) else None
    return {key: (usage[key] if isinstance(usage, dict)
                  and isinstance(usage.get(key), (int, float))
                  and not isinstance(usage[key], bool) and usage[key] >= 0 else "unknown")
            for key in ("prompt_tokens", "completion_tokens", "total_tokens")}


def string_assertions(case, parsed, truncated):
    content = None
    if isinstance(parsed, dict):
        choices = parsed.get("choices")
        if isinstance(choices, list) and choices and isinstance(choices[0], dict):
            message = choices[0].get("message")
            if isinstance(message, dict) and isinstance(message.get("content"), str):
                content = message["content"]
    checks = []
    for field in ("contains", "not_contains"):
        for expected in case.get(field, []):
            outcome = "unknown"
            if content is not None and not truncated:
                present = expected in content
                outcome = "pass" if (present if field == "contains" else not present) else "fail"
            checks.append({"type": field, "expected": expected, "result": outcome})
    return checks


def probe(case, endpoint, model, secret, timeout, body_limit, response_limit):
    payload = {"model": model, "messages": case["messages"], "stream": False}
    body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
    record = {"case_id": case["id"], "status": "not_sent", "http_status": "unknown",
              "first_response_ms": "unknown", "total_latency_ms": "unknown",
              "provider_usage": usage_fields(None), "response_truncated": False,
              "response_incomplete": False, "model_response": "unknown", "string_assertions": string_assertions(case, None, False)}
    if len(body) > body_limit:
        record["status"] = "request_body_limit"
        return record
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if secret:
        headers["Authorization"] = "Bearer " + secret
    request = urllib.request.Request(endpoint, data=body, headers=headers, method="POST")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    started = time.monotonic()
    response = None
    data = bytearray()
    try:
        try:
            response = opener.open(request, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error  # bounded error body is still useful protocol evidence
        record["http_status"] = response.code
        # read1 returns available bytes without waiting to fill the entire bound.
        deadline = started + timeout
        while len(data) <= response_limit:
            if time.monotonic() >= deadline:
                raise TimeoutError()
            chunk = response.read1(min(4096, response_limit + 1 - len(data)))
            if not chunk:
                break
            if record["first_response_ms"] == "unknown":
                record["first_response_ms"] = round((time.monotonic() - started) * 1000, 3)
            data.extend(chunk)
        truncated = len(data) > response_limit
        record["response_truncated"] = truncated
        raw = redact(bytes(data[:response_limit]).decode("utf-8", "replace"), secret, truncated)
        parsed = None
        if not truncated:
            try:
                parsed = json.loads(raw, parse_constant=reject_constant)
            except (ValueError, RecursionError):
                pass
        record["model_response"] = parsed if parsed is not None else {"raw_text": raw}
        record["provider_usage"] = usage_fields(parsed)
        record["string_assertions"] = string_assertions(case, parsed, truncated)
        record["status"] = ("http_error" if not 200 <= response.code < 300 else
                            "response_truncated" if truncated else
                            "invalid_json" if parsed is None else "received")
        # Model tool_calls are recorded as data only. There is NO dispatch/execution path.
    except Exception as error:
        # Exception strings can contain credentials or untrusted response text: never log them.
        record["status"] = "transport_error"
        record["error_type"] = type(error).__name__
        record["response_incomplete"] = True
        if data:
            record["response_truncated"] = True
            record["model_response"] = {"raw_text": redact(bytes(data[:response_limit]).decode("utf-8", "replace"), secret, True)}
    finally:
        if response is not None:
            response.close()
        record["total_latency_ms"] = round((time.monotonic() - started) * 1000, 3)
    return record


def parser():
    p = SafeArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter,
                           allow_abbrev=False)
    p.add_argument("--cases", required=True)
    p.add_argument("--execute", action="store_true", help="explicitly permit model HTTP requests")
    p.add_argument("--endpoint", help="user-supplied full http(s) /chat/completions URL")
    p.add_argument("--model", help="user-supplied gateway model identifier")
    p.add_argument("--api-key-env", default="MODEL_API_KEY", help="environment variable NAME, not a key")
    p.add_argument("--timeout", type=timeout_value, default=30.0,
                   help="socket timeout and response-read deadline, seconds (<=120); not a DNS wall-clock guarantee")
    p.add_argument("--max-body-bytes", type=bounded_int, default=65536)
    p.add_argument("--max-response-bytes", type=bounded_int, default=65536)
    p.add_argument("--output", help="write report here instead of stdout; cannot alias input cases")
    return p


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    secret = ""
    try:
        if not re.match(r"^[A-Za-z_][A-Za-z0-9_]{0,127}$", args.api_key_env):
            raise ValueError("api-key-env must be an environment variable name")
        secret = os.environ.get(args.api_key_env, "")
        if len(secret) > 4096 or any(ord(c) < 32 or ord(c) > 126 for c in secret):
            raise ValueError("API key must be <=4096 printable ASCII characters")
        source = Path(args.cases).resolve()
        if args.output:
            target = Path(args.output).resolve()
            if target == source or (target.exists() and os.path.samefile(str(source), str(target))):
                raise ValueError("output cannot overwrite input cases")
        if args.endpoint:
            endpoint_url(args.endpoint)
        if args.execute and (not args.endpoint or not args.model or not args.model.strip()):
            raise ValueError("--execute requires explicit --endpoint and --model")
        if args.model and (len(args.model) > 256 or any(ord(c) < 32 for c in args.model)):
            raise ValueError("model must be <=256 characters without control characters")
        cases = load_cases(source)
        report = {"mode": "execute" if args.execute else "dry_run", "notice": NOTICE,
                  "endpoint": args.endpoint or "unknown", "model": args.model or "unknown", "cases": []}
        for case in cases:
            if args.execute:
                result = probe(case, args.endpoint, args.model, secret, args.timeout,
                               args.max_body_bytes, args.max_response_bytes)
            else:
                body = json.dumps({"model": args.model or "USER_MODEL_REQUIRED", "messages": case["messages"],
                                   "stream": False}, ensure_ascii=False).encode("utf-8")
                result = {"case_id": case["id"], "status": "dry_run_no_network",
                          "request_bytes": len(body), "within_body_limit": len(body) <= args.max_body_bytes,
                          "provider_usage": usage_fields(None), "first_response_ms": "unknown",
                          "total_latency_ms": "unknown", "string_assertions": string_assertions(case, None, False)}
            report["cases"].append(result)
        serialized = redact(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False), secret) + "\n"
        if args.output:
            with open(str(target), "w", encoding="utf-8") as destination:
                destination.write(serialized)
        else:
            sys.stdout.write(serialized)
        return 0
    except (ValueError, OSError, RecursionError) as error:
        # Do not echo invalid arguments, file content, endpoint credentials or secret values.
        sys.stderr.write("probe failed: invalid input or I/O (" + type(error).__name__ + ")\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())

