#!/usr/bin/env python3
"""Buzón Wally Watch: un repo privado de GitHub como tablón entre el reloj y el agente.

Ficheros en el repo Z43L/wally-watch-mailbox:
  inbox.json  <- COLA de mensajes: [{id, ts, status: pending, text}, ...]
  outbox.json <- el agente escribe {reply_to, ts, text}

Uso:
  mailbox.py init                          crea el repo privado y los ficheros semilla
  mailbox.py inbox                         muestra la cola de pendientes (la usa el cron)
  mailbox.py send "texto"                  añade un mensaje a la cola (test / terminal)
  mailbox.py outbox <id> --text-file F     escribe la respuesta del agente
  mailbox.py done <id>                     saca el mensaje <id> de la cola
"""
import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, "/opt/hatch/skills/skill-creator/bin")
from dynamic_credentials import add_surrogate_to_request, read_json_response

API = "https://api.github.com"
ALLOWED = ["api.github.com"]
OWNER = "Z43L"
REPO_NAME = "wally-watch-mailbox"
REPO = f"{OWNER}/{REPO_NAME}"


def api(method, path, body=None):
    url = API + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data:
        req.add_header("Content-Type", "application/json")
    add_surrogate_to_request(
        req, "custom.github", entry_name="access_token", allowed_hosts=ALLOWED
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, read_json_response(resp)
    except urllib.error.HTTPError as e:
        try:
            detail = e.read().decode()[:300]
        except Exception:
            detail = ""
        raise SystemExit(f"GitHub API {method} {path} -> HTTP {e.code}: {detail}")


def get_file(path):
    _, data = api("GET", f"/repos/{REPO}/contents/{path}")
    raw = base64.b64decode(data["content"])
    return data["sha"], json.loads(raw)


def put_file(path, obj, message):
    try:
        sha, _ = get_file(path)
    except SystemExit as e:
        if "HTTP 404" in str(e):
            sha = None
        else:
            raise
    body = {
        "message": message,
        "content": base64.b64encode(
            json.dumps(obj, ensure_ascii=False).encode()
        ).decode(),
    }
    if sha:
        body["sha"] = sha
    api("PUT", f"/repos/{REPO}/contents/{path}", body)


def _write_inbox(sha, queue, message):
    body = {
        "message": message,
        "content": base64.b64encode(
            json.dumps(queue, ensure_ascii=False).encode()
        ).decode(),
    }
    if sha:
        body["sha"] = sha
    api("PUT", f"/repos/{REPO}/contents/inbox.json", body)


def _read_inbox():
    """Devuelve (sha, cola de pendientes). Migra el formato antiguo (dict) a lista."""
    try:
        sha, obj = get_file("inbox.json")
    except SystemExit as e:
        if "HTTP 404" in str(e):
            return None, []
        raise
    if isinstance(obj, dict):
        obj = [obj] if obj.get("status") == "pending" else []
    if not isinstance(obj, list):
        obj = []
    pending = [
        m for m in obj if isinstance(m, dict) and m.get("status") == "pending"
    ]
    return sha, pending


def cmd_init(_):
    try:
        _, data = api(
            "POST",
            "/user/repos",
            {
                "name": REPO_NAME,
                "private": True,
                "auto_init": True,
                "description": "Buzón privado reloj<->agente para Wally Watch (prototipo)",
            },
        )
        print("repo:", data.get("full_name"))
    except SystemExit as e:
        if "HTTP 422" in str(e):
            print("repo ya existe, continúo con la semilla")
        else:
            raise
    put_file(
        "inbox.json",
        [],
        "buzon: seed inbox",
    )
    put_file(
        "outbox.json",
        {"reply_to": None, "ts": 0, "text": ""},
        "buzon: seed outbox",
    )
    print("seed ok")


def cmd_inbox(_):
    _, queue = _read_inbox()
    print(json.dumps(queue, ensure_ascii=False))


def cmd_send(args):
    msg_id = uuid.uuid4().hex[:12]
    sha, queue = _read_inbox()
    queue.append(
        {
            "id": msg_id,
            "ts": int(time.time()),
            "status": "pending",
            "text": args.text,
        }
    )
    _write_inbox(sha, queue, "buzon: mensaje de test")
    print("sent id:", msg_id)


def cmd_outbox(args):
    if args.text_file:
        with open(args.text_file, encoding="utf-8") as f:
            text = f.read().strip()
    else:
        text = (args.text or "").strip()
    if not text:
        raise SystemExit("respuesta vacía")
    put_file(
        "outbox.json",
        {"reply_to": args.reply_to, "ts": int(time.time()), "text": text},
        "buzon: respuesta del agente",
    )
    print("outbox ok ->", args.reply_to)


def cmd_done(args):
    sha, queue = _read_inbox()
    queue = [m for m in queue if m.get("id") != args.id]
    _write_inbox(sha, queue, "buzon: mensaje atendido")
    print("inbox done ->", args.id)


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("init")
    sub.add_parser("inbox")
    s = sub.add_parser("send")
    s.add_argument("text")
    o = sub.add_parser("outbox")
    o.add_argument("reply_to")
    o.add_argument("text", nargs="?")
    o.add_argument("--text-file")
    d = sub.add_parser("done")
    d.add_argument("id")
    args = p.parse_args()
    {
        "init": cmd_init,
        "inbox": cmd_inbox,
        "send": cmd_send,
        "outbox": cmd_outbox,
        "done": cmd_done,
    }[args.cmd](args)


main()
