#!/usr/bin/env python3
"""Decode the most recent email the Graph Mail plugin sent to the MockServer dummy."""

import argparse
import base64
import json
import pathlib
import re
import sys
import tempfile
import urllib.error
import urllib.request

# sendMail carries the whole message; the draft path posts the message then each attachment.
SEND_MAIL_RE = re.compile(r"/v1\.0/users/[^/]+/sendMail")
CREATE_DRAFT_RE = re.compile(r"/v1\.0/users/[^/]+/messages$")
UPLOAD_SESSION_RE = re.compile(r"createUploadSession")
ADD_ATTACHMENT_RE = re.compile(r"/v1\.0/users/[^/]+/messages/[^/]+/attachments$")


def fetch_requests(base_url):
    """MockServer exposes its request log via PUT /mockserver/retrieve."""
    request = urllib.request.Request(
        f"{base_url}/mockserver/retrieve?type=REQUESTS&format=JSON",
        method="PUT",
        data=b"",
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as resp:
            return json.load(resp)
    except urllib.error.URLError as exc:
        sys.exit(
            f"Could not reach the MockServer dummy at {base_url}: {exc}\n"
            "Is it up?  docker compose -f backend/app/docker-compose.yml up -d mockserver"
        )


def body_text(entry):
    """MockServer records the body either as a string or as a {type, json/string} object."""
    body = entry.get("body")
    if body is None:
        return None
    if isinstance(body, str):
        return body
    if isinstance(body, dict):
        for key in ("json", "string", "rawBytes"):
            if key in body:
                value = body[key]
                if key == "rawBytes":
                    return base64.b64decode(value).decode("utf-8", "replace")
                return value if isinstance(value, str) else json.dumps(value)
    return None


def addresses(message, field):
    return [r["emailAddress"]["address"] for r in message.get(field, [])]


def describe(entry, outdir, index):
    raw = body_text(entry)
    payload = json.loads(raw)
    # sendMail wraps the message; createDraft posts the message object directly.
    message = payload.get("message", payload)
    flow = "inline sendMail" if "message" in payload else "draft"

    print(f"\n=== send #{index} — {flow} ===")
    print(f"  path:      {entry.get('path')}")
    print(f"  subject:   {message.get('subject')!r}")
    print(f"  to:        {', '.join(addresses(message, 'toRecipients')) or '-'}")
    print(f"  cc:        {', '.join(addresses(message, 'ccRecipients')) or '-'}")
    print(f"  bcc:       {', '.join(addresses(message, 'bccRecipients')) or '-'}")
    print(f"  replyTo:   {', '.join(addresses(message, 'replyTo')) or '-'}")
    if "saveToSentItems" in payload:
        print(f"  saveToSentItems: {payload['saveToSentItems']}")

    html = message.get("body", {}).get("content", "")
    body_file = outdir / f"send-{index}-body.html"
    body_file.write_text(html, encoding="utf-8")
    print(f"  body:      {len(html)} chars of HTML -> {body_file}")

    inline = message.get("attachments") or []
    if inline:
        print(f"  attachments ({len(inline)}, inline base64):")
        for attachment in inline:
            content = base64.b64decode(attachment["contentBytes"])
            # A bare UUID here means the storage metadata lookup missed.
            safe = pathlib.Path(attachment["name"]).name or "attachment"
            path = outdir / f"send-{index}-{safe}"
            path.write_bytes(content)
            print(
                f"    - name={attachment['name']!r} "
                f"type={attachment.get('contentType')!r} "
                f"{len(content)} bytes -> {path}"
            )
    elif flow.startswith("draft"):
        print("  attachments: added to the draft separately (see below)")
    else:
        print("  attachments: none")


def describe_posted_attachments(entries, outdir):
    """Attachments below the upload-session minimum, POSTed onto the draft one by one."""
    posted = []
    for entry in entries:
        if ADD_ATTACHMENT_RE.search(entry.get("path", "")):
            raw = body_text(entry)
            if raw:
                posted.append(json.loads(raw))
    if not posted:
        return
    print("\n=== POST .../attachments requests (small files on the draft path) ===")
    for index, attachment in enumerate(posted, start=1):
        content = base64.b64decode(attachment.get("contentBytes") or "")
        safe = pathlib.Path(attachment.get("name") or "attachment").name or "attachment"
        path = outdir / f"draft-attachment-{index}-{safe}"
        path.write_bytes(content)
        print(
            f"  - name={attachment.get('name')!r} "
            f"type={attachment.get('contentType')!r} "
            f"{len(content)} bytes -> {path}"
        )


def describe_upload_sessions(entries):
    sessions = []
    for entry in entries:
        if UPLOAD_SESSION_RE.search(entry.get("path", "")):
            raw = body_text(entry)
            if raw:
                sessions.append(json.loads(raw).get("AttachmentItem", {}))
    if not sessions:
        return
    print("\n=== createUploadSession requests (attachment names on the large-file path) ===")
    for item in sessions:
        print(
            f"  - name={item.get('name')!r} "
            f"type={item.get('contentType')!r} size={item.get('size')}"
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:1080", help="MockServer base URL")
    parser.add_argument("--all", action="store_true", help="show every captured send")
    parser.add_argument("--outdir", help="where to write body/attachments (default: a temp dir)")
    args = parser.parse_args()

    entries = fetch_requests(args.url.rstrip("/"))
    if not entries:
        sys.exit("MockServer's request log is empty — no email has been sent yet.")

    sends = [
        e
        for e in entries
        if (SEND_MAIL_RE.search(e.get("path", "")) or CREATE_DRAFT_RE.search(e.get("path", "")))
        and body_text(e)
    ]
    if not sends:
        sys.exit(
            f"No sendMail / createDraft requests logged ({len(entries)} requests captured).\n"
            "The plugin may have failed before reaching Graph — check the app log."
        )

    # MockServer returns oldest-first; show the newest by default.
    sends.reverse()

    outdir = (
        pathlib.Path(args.outdir)
        if args.outdir
        else pathlib.Path(tempfile.mkdtemp(prefix="graph-mail-"))
    )
    outdir.mkdir(parents=True, exist_ok=True)

    for index, entry in enumerate(sends if args.all else sends[:1], start=1):
        describe(entry, outdir, index)
    describe_posted_attachments(entries, outdir)
    describe_upload_sessions(entries)
    print(f"\nWrote body/attachments to {outdir}")


if __name__ == "__main__":
    main()
