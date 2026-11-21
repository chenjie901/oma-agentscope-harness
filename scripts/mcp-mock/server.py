#!/usr/bin/env python3
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOOLS = [
    {
        "name": "echo",
        "description": "Echo the provided text back, prefixed with MCP_OK.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "text to echo"}},
            "required": ["text"],
        },
    },
    {
        "name": "disabled_tool",
        "description": "A tool that should be disabled via mcp_config.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "make_big",
        "description": "Return a very large text payload to exercise persistence.",
        "inputSchema": {
            "type": "object",
            "properties": {"size": {"type": "integer"}},
            "required": ["size"],
        },
    },
]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("mcp-mock: " + (fmt % args) + "\n")

    def _reply(self, payload, session=None):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache")
        if session:
            self.send_header("Mcp-Session-Id", session)
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        try:
            msg = json.loads(raw)
        except Exception:
            self._reply({"jsonrpc": "2.0", "id": None,
                         "error": {"code": -32700, "message": "parse error"}})
            return
        method = msg.get("method")
        rid = msg.get("id")
        sys.stderr.write("mcp-mock RECV: " + json.dumps(msg)[:500] + "\n")
        session = self.headers.get("Mcp-Session-Id")
        if method == "initialize":
            self._reply({
                "jsonrpc": "2.0", "id": rid,
                "result": {
                    "protocolVersion": msg.get("params", {}).get("protocolVersion", "2025-03-26"),
                    "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {"name": "oma-mcp-mock", "version": "1.0.0"},
                },
            }, session="mock-session-1")
        elif method == "notifications/initialized":
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif method == "tools/list":
            self._reply({"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            params = msg.get("params", {})
            name = params.get("name")
            args = params.get("arguments", {})
            if name == "echo":
                text = "MCP_OK: " + str(args.get("text", ""))
            elif name == "make_big":
                text = "B" * int(args.get("size", 100100))
            else:
                self._reply({"jsonrpc": "2.0", "id": rid, "result": {
                    "content": [{"type": "text", "text": "unknown tool: " + str(name)}],
                    "isError": True}})
                return
            self._reply({"jsonrpc": "2.0", "id": rid, "result": {
                "content": [{"type": "text", "text": text}], "isError": False}})
        else:
            self._reply({"jsonrpc": "2.0", "id": rid,
                         "error": {"code": -32601, "message": "method not found: " + str(method)}})

    def do_DELETE(self):
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 38099
    sys.stderr.write("mcp-mock listening on 127.0.0.1:%d\n" % port)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
