"""Offline tests. Only loopback mock HTTP; no external providers or Maven."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from unittest import mock

SPEC = importlib.util.spec_from_file_location("probe_model_protocol", str(Path(__file__).with_name("probe_model_protocol.py")))
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)
FIXTURE = Path(__file__).with_name("model_protocol_cases.jsonl")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.server.requests.append({"body": json.loads(body.decode("utf-8")),
                                     "authorization": self.headers.get("Authorization")})
        mode = self.server.mode
        if mode == "disconnect":
            self.connection.shutdown(socket.SHUT_RDWR)
            self.connection.close()
            return
        if mode == "timeout":
            time.sleep(0.15)
        status = 503 if mode == "http_error" else 302 if mode == "redirect" else 200
        if mode == "invalid":
            raw = b"not-json"
        elif mode == "large":
            raw = ("x" * 5000).encode("ascii")
        elif mode == "secret":
            raw = json.dumps({"choices": [{"message": {"content": "echo " + self.server.secret}}],
                              "also": self.server.secret}, ensure_ascii=True).encode("utf-8")
        else:
            response = {"choices": [{"message": {"role": "assistant", "content": "LATEST_FORMAT_OK synthetic_ok 格式确认",
                        "tool_calls": [{"id": "synthetic", "type": "function", "function": {
                            "name": "DO_NOT_EXECUTE", "arguments": "{\"command\":\"never run\"}"}}]},
                        "finish_reason": "stop"}]}
            if mode == "usage":
                response["usage"] = {"prompt_tokens": 4, "completion_tokens": 2}
            raw = json.dumps(response, ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            if mode == "redirect":
                self.send_header("Location", "http://127.0.0.1:1/chat/completions")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            if mode == "partial_timeout":
                self.wfile.write(raw[:20])
                self.wfile.flush()
                time.sleep(0.15)
                self.wfile.write(raw[20:])
                return
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


class ProbeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.output = Path(self.temp.name) / "报告.json"
        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.server.requests = []
        self.server.mode = "success"
        self.server.secret = "test-only-secret-NEVER-PERSIST"
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.daemon = True
        self.thread.start()
        self.endpoint = "http://127.0.0.1:{}/v1/chat/completions".format(self.server.server_port)
        self.case = probe.load_cases(FIXTURE)[0]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def main(self, extra=None):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return probe.main(["--cases", str(FIXTURE), "--output", str(self.output)] + (extra or []))

    def execute_args(self):
        return ["--execute", "--endpoint", self.endpoint, "--model", "synthetic-local-model"]

    def one(self, **kwargs):
        return probe.probe(self.case, self.endpoint, "synthetic", kwargs.get("secret", ""),
                           kwargs.get("timeout", 1), kwargs.get("body_limit", 65536), kwargs.get("response_limit", 65536))

    def test_fixture_default_and_even_supplied_endpoint_never_open_network(self):
        with mock.patch.object(probe.urllib.request, "build_opener", side_effect=AssertionError("network forbidden")):
            self.assertEqual(0, self.main())
            report = json.loads(self.output.read_text(encoding="utf-8"))
            self.assertEqual("dry_run", report["mode"])
            self.assertEqual(3, len(report["cases"]))
            self.assertEqual("unknown", report["cases"][0]["provider_usage"]["total_tokens"])
            self.assertEqual(0, self.main(["--endpoint", self.endpoint, "--model", "synthetic"]))
        self.assertEqual([], self.server.requests)

    def test_execution_requires_both_user_endpoint_and_model(self):
        for args in (["--execute"], ["--execute", "--endpoint", self.endpoint], ["--execute", "--model", "synthetic"]):
            self.assertEqual(2, self.main(args))
        self.assertEqual([], self.server.requests)

    def test_cli_errors_do_not_echo_secret_arguments(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors), self.assertRaises(SystemExit):
            probe.main(["--cases", str(FIXTURE), "--api-key", self.server.secret])
        self.assertNotIn(self.server.secret, errors.getvalue())

    def test_unknown_missing_content_assertions_and_nonfinite_usage(self):
        checks = probe.string_assertions(self.case, {"choices": []}, False)
        self.assertTrue(all(x["result"] == "unknown" for x in checks))
        with self.assertRaises(ValueError):
            json.loads('{"usage":{"total_tokens":Infinity}}', parse_constant=probe.reject_constant)

    def test_explicit_success_records_nonstreaming_response_and_never_dispatches_tools(self):
        with mock.patch.dict(os.environ, {"MODEL_API_KEY": self.server.secret}):
            self.assertEqual(0, self.main(self.execute_args()))
        self.assertEqual(3, len(self.server.requests))
        sent = self.server.requests[0]
        self.assertEqual("Bearer " + self.server.secret, sent["authorization"])
        self.assertEqual({"model", "messages", "stream"}, set(sent["body"]))
        self.assertIs(False, sent["body"]["stream"])
        report = json.loads(self.output.read_text(encoding="utf-8"))
        result = report["cases"][0]
        self.assertEqual("received", result["status"])
        self.assertIsInstance(result["first_response_ms"], (float, int))
        self.assertGreaterEqual(result["total_latency_ms"], result["first_response_ms"])
        self.assertEqual("pass", result["string_assertions"][0]["result"])
        self.assertIn("tool_calls", result["model_response"]["choices"][0]["message"])
        self.assertNotIn(self.server.secret, self.output.read_text(encoding="utf-8"))
        self.assertIn("NOT SSE TTFT", report["notice"])

    def test_echoed_key_is_redacted_in_file_stdout_and_errors(self):
        self.server.mode = "secret"
        captured, errors = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, {"MODEL_API_KEY": self.server.secret}):
            with contextlib.redirect_stdout(captured), contextlib.redirect_stderr(errors):
                self.assertEqual(0, probe.main(["--cases", str(FIXTURE)] + self.execute_args()))
                self.assertEqual(2, probe.main(["--cases", str(FIXTURE), "--endpoint", "http://user:" + self.server.secret + "@host/chat/completions"]))
            self.assertEqual(0, self.main(self.execute_args()))
        self.assertNotIn(self.server.secret, captured.getvalue() + errors.getvalue() + self.output.read_text(encoding="utf-8"))
        self.assertIn("REDACTED", captured.getvalue())
        self.assertEqual("foo [REDACTED_PARTIAL]", probe.redact("foo " + self.server.secret[:8], self.server.secret, True))

    def test_unknown_usage_is_not_inferred_or_summed(self):
        result = self.one()
        self.assertEqual({"prompt_tokens": "unknown", "completion_tokens": "unknown", "total_tokens": "unknown"}, result["provider_usage"])
        self.server.mode = "usage"
        usage = self.one()["provider_usage"]
        self.assertEqual(4, usage["prompt_tokens"])
        self.assertEqual(2, usage["completion_tokens"])
        self.assertEqual("unknown", usage["total_tokens"])
        self.assertEqual("unknown", probe.usage_fields({"usage": {"total_tokens": True}})["total_tokens"])

    def test_truncated_response_is_explicit_assertions_unknown(self):
        self.server.mode = "large"
        result = self.one(response_limit=64)
        self.assertTrue(result["response_truncated"])
        self.assertEqual("response_truncated", result["status"])
        self.assertEqual(64, len(result["model_response"]["raw_text"]))
        self.assertEqual("unknown", result["string_assertions"][0]["result"])

    def test_request_body_budget_prevents_network(self):
        with mock.patch.object(probe.urllib.request, "build_opener", side_effect=AssertionError("network forbidden")):
            self.assertEqual("request_body_limit", self.one(body_limit=1)["status"])
        self.assertEqual([], self.server.requests)

    def test_timeout_keeps_partial_response_and_marks_it_incomplete(self):
        self.server.mode = "partial_timeout"
        result = self.one(timeout=0.05)
        self.assertEqual("transport_error", result["status"])
        self.assertTrue(result["response_incomplete"])
        self.assertTrue(result["response_truncated"])
        self.assertTrue(result["model_response"]["raw_text"])
        self.assertEqual("unknown", result["string_assertions"][0]["result"])

    def test_http_error_invalid_json_disconnect_timeout_and_no_redirect(self):
        for mode, expected in (("http_error", "http_error"), ("invalid", "invalid_json"),
                               ("disconnect", "transport_error"), ("redirect", "http_error"),
                               ("timeout", "transport_error")):
            self.server.mode = mode
            result = self.one(timeout=0.05 if mode == "timeout" else 1)
            self.assertEqual(expected, result["status"], mode)
            self.assertIsInstance(result["total_latency_ms"], (int, float))
        self.assertEqual(5, len(self.server.requests))

    def test_endpoint_restrictions(self):
        for value in ("file:///a/chat/completions", "ftp://host/chat/completions", "http://u:p@host/chat/completions",
                      "http://host/chat/completions?q=1", "http://host/chat/completions#x", "http://host:99999/chat/completions",
                      "http://host:0/chat/completions", "http://host/a\n/chat/completions", "http://host/v1"):
            with self.assertRaises(ValueError, msg=value):
                probe.endpoint_url(value)

    def test_no_arbitrary_tool_or_extra_request_parameters(self):
        for extra in ({"tools": []}, {"temperature": 1}, {"tool_choice": "auto"}):
            case = dict(self.case)
            case.update(extra)
            path = Path(self.temp.name) / "bad.jsonl"
            path.write_text(json.dumps(case), encoding="utf-8")
            with self.assertRaises(ValueError):
                probe.load_cases(path)
        case = dict(self.case)
        case["messages"] = [{"role": "user", "content": "x", "tool_calls": []}]
        path.write_text(json.dumps(case), encoding="utf-8")
        with self.assertRaises(ValueError):
            probe.load_cases(path)

    def test_output_cannot_overwrite_input_or_hardlink(self):
        path = Path(self.temp.name) / "input.jsonl"
        original = FIXTURE.read_bytes()
        path.write_bytes(original)
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, probe.main(["--cases", str(path), "--output", str(path)]))
            link = Path(self.temp.name) / "alias.jsonl"
            os.link(str(path), str(link))
            self.assertEqual(2, probe.main(["--cases", str(path), "--output", str(link)]))
        self.assertEqual(original, path.read_bytes())

    def test_input_size_budget(self):
        path = Path(self.temp.name) / "big.jsonl"
        path.write_bytes(b" " * (probe.MAX_CASE_BYTES + 1))
        with self.assertRaises(ValueError):
            probe.load_cases(path)

    def test_assertion_failure_is_only_string_failure(self):
        checks = probe.string_assertions(self.case, {"choices": [{"message": {"content": "<old>"}}]}, False)
        self.assertEqual(["fail", "fail"], [x["result"] for x in checks])
        self.assertNotIn("score", json.dumps(checks))


if __name__ == "__main__":
    unittest.main()
