#!/usr/bin/env python3
"""Runs opencode headless and reports to Telegram: one progress message, the final answer, and the files. No links."""
import json, os, re, signal, subprocess, sys, threading, time, urllib.error, urllib.parse, urllib.request
from pathlib import Path

WS = Path(os.environ.get("GITHUB_WORKSPACE", os.getcwd())).resolve()
OUT = WS / "outputs"
def _payload():
    try: return json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text()).get("client_payload") or {}
    except Exception: return {}
PAY = _payload()   # message text and ids come from the event file, so they never appear in (possibly public) Actions logs
TG = os.environ.get("TG_TOKEN", "")
CHAT = os.environ.get("CHAT") or str(PAY.get("chat_id") or "")
MID = os.environ.get("MID") or str(PAY.get("mid") or "")
REQ = os.environ.get("REQ") or str(PAY.get("req") or "")      # id of the user's request message (everything replies to it)
MODEL, MSG, CTX = os.environ.get("MODEL", ""), os.environ.get("MSG") or str(PAY.get("text") or ""), os.environ.get("CTX", "")
QUIET = os.environ.get("QUIET") == "1"   # public repo: do not echo the conversation into the Actions log
GH_REPO, GH_TOKEN_API = os.environ.get("GITHUB_REPOSITORY", ""), os.environ.get("GH_API_TOKEN", "")
GH_API = os.environ.get("GH_API_BASE", "https://api.github.com")
STOP_POLL = float(os.environ.get("STOP_POLL", "8"))
CHAT_LOG = WS / "memory" / "chat.jsonl"
START = time.time()
HARD = int(os.environ.get("HARD_SECS", "7200"))   # absolute cap: 2 hours
IDLE = int(os.environ.get("IDLE_SECS", "1800"))   # kill only if completely silent this long (stuck)
FREE = False
MAX_RETRY = int(os.environ.get("RETRY_MAX", "6"))
BACKOFFS = [int(x) for x in os.environ.get("RETRY_BACKOFF", "20,45,90,150,240,300").split(",")]
MODEL_STALL = int(os.environ.get("MODEL_STALL", "480"))   # quiet this long with no tool running = model call is stuck
RL_GRACE = int(os.environ.get("RL_GRACE", "20"))          # after a 429 message, wait this long for recovery before restarting
RL_RE = re.compile(r"\b429\b|rate.?limit|free usage limit|too many requests", re.I)
RESUME_NOTE = "\n\n[Note: your previous attempt was interrupted by a temporary API error (rate limit). Continue from where you left off; do not redo finished work.]"
ICON = {"bash": "⚡", "read": "📖", "write": "✍️", "edit": "🧩", "glob": "🔎", "grep": "🔎", "webfetch": "🌐", "websearch": "🔍", "task": "🤝", "todowrite": "📝", "skill": "🎓"}
S = dict(retries=0, retry="", retry_msg="", rl=0.0, prod=time.time(), nobin=False, steps=0, tools=0, last="", kind="", label="", body="", lang="", final="", done=False, beat=time.time(), why="")
STEP_TEXT, RAW = [], []


# ---------- Telegram HTML formatting (real code blocks) ----------
LANG = {".py": "python", ".js": "javascript", ".mjs": "javascript", ".ts": "typescript", ".html": "html", ".css": "css", ".json": "json",
        ".md": "markdown", ".sh": "bash", ".yml": "yaml", ".yaml": "yaml", ".c": "c", ".cpp": "cpp", ".rs": "rust", ".go": "go",
        ".glsl": "glsl", ".sql": "sql", ".xml": "xml", ".toml": "toml", ".java": "java", ".php": "php"}

def esc(s): return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
def lang_for(path): return LANG.get(Path(str(path)).suffix.lower(), "")
def pre(code, lang=""): return f'<pre><code{f" class=\"language-{lang}\"" if lang else ""}>{esc(code)}</code></pre>'

def md_to_html(md):
    """Convert the model's Markdown (code fences, `code`, **bold**, # headings, - bullets) to Telegram HTML."""
    store = []
    def keep(h): store.append(h); return f"\x00{len(store) - 1}\x00"
    md = re.sub(r"```([\w+#-]*)[ \t]*\n(.*?)```", lambda m: keep(pre(m.group(2).rstrip("\n"), m.group(1))), md, flags=re.S)
    md = re.sub(r"`([^`\n]+)`", lambda m: keep(f"<code>{esc(m.group(1))}</code>"), md)
    md = esc(md)
    bold = lambda m: m.group(0) if "\x00" in m.group(1) else f"<b>{m.group(1)}</b>"
    md = re.sub(r"(?m)^#{1,6}[ \t]+(.+)$", bold, md)
    md = re.sub(r"\*\*([^*\n]+?)\*\*", bold, md)
    md = re.sub(r"(?m)^[ \t]*[-*][ \t]+", "• ", md)
    return re.sub(r"\x00(\d+)\x00", lambda m: store[int(m.group(1))], md)

def split_md(md, limit=3300):
    """Split Markdown into chunks under the limit without breaking code fences (close and re-open them)."""
    lines = []
    for ln in md.split("\n"):
        lines += [ln[i:i + limit] for i in range(0, len(ln), limit)] or [""]
    parts, cur, size, fence = [], [], 0, None
    for ln in lines:
        if size + len(ln) + 1 > limit and cur:
            if fence is not None:
                cur.append("```"); parts.append("\n".join(cur)); cur = ["```" + fence]; size = len(cur[0])
            else:
                parts.append("\n".join(cur)); cur, size = [], 0
        cur.append(ln); size += len(ln) + 1
        if ln.strip().startswith("```"):
            fence = None if fence is not None else ln.strip()[3:].strip()
    if cur: parts.append("\n".join(cur))
    return parts

def send_md(text):
    for part in split_md(text):
        r = tg("sendMessage", chat_id=CHAT, text=md_to_html(part), parse_mode="HTML", disable_web_page_preview="true")
        if not r.get("ok"): tg("sendMessage", chat_id=CHAT, text=part[:4000])

def reply_json():
    try: return json.dumps({"message_id": int(REQ), "allow_sending_without_reply": True}) if REQ else ""
    except ValueError: return ""

def tg(method, **data):
    if not TG: return {}
    if method == "sendMessage" and "reply_parameters" not in data and reply_json(): data["reply_parameters"] = reply_json()
    try:
        req = urllib.request.Request(f"https://api.telegram.org/bot{TG}/{method}", data=urllib.parse.urlencode(data).encode())
        return json.load(urllib.request.urlopen(req, timeout=20))
    except Exception as e:
        print("tg error", method, e, flush=True); return {}


# ---------- plan (todos) in its own message, with the time each item was ticked ----------
PLAN = dict(mid="", items=[], t={}, last="")
def mmss(sec): return f"{sec // 60:02d}:{sec % 60:02d}"

def plan_html(foot=""):
    items = PLAN["items"]; done = sum(1 for _, st in items if st == "completed")
    lines = [f"📋 <b>برنامه</b> · {done}/{len(items)} تیک خورده"]
    for c, st in items:
        r = PLAN["t"].get(c, {})
        if st == "completed":
            dur = f" ({mmss(r['done'] - r['start'])})" if "start" in r and r["done"] >= r["start"] else ""
            lines.append(f"✅ {mmss(r['done'])} · {esc(c)}{dur}")
        elif st == "in_progress": lines.append(f"🔄 از {mmss(r['start'])} · {esc(c)}" if "start" in r else f"🔄 {esc(c)}")
        elif st == "cancelled": lines.append(f"⛔ {esc(c)}")
        else: lines.append(f"⬜ {esc(c)}")
    if foot: lines.append(esc(foot))
    return "\n".join(lines)[:4000]

def plan_send(foot=""):
    if not PLAN["items"]: return
    h = plan_html(foot)
    if h == PLAN["last"]: return
    if not PLAN["mid"]:
        r = tg("sendMessage", chat_id=CHAT, text=h, parse_mode="HTML")
        PLAN["mid"] = str((r.get("result") or {}).get("message_id", ""))
    else:
        tg("editMessageText", chat_id=CHAT, message_id=PLAN["mid"], text=h, parse_mode="HTML")
    PLAN["last"] = h

def plan_update(todos):
    now = int(time.time() - START); items = []
    for td in todos or []:
        c, st = str(td.get("content", "")).strip(), td.get("status", "pending"); items.append((c, st))
        r = PLAN["t"].setdefault(c, {})
        if st == "in_progress" and "start" not in r: r["start"] = now
        if st == "completed" and "done" not in r: r["done"] = now
    PLAN["items"] = items
    plan_send()

def load_ctx():
    try: lines = CHAT_LOG.read_text(errors="ignore").splitlines()[-6:]
    except OSError: return ""
    out = []
    for l in lines:
        try: d = json.loads(l); out.append(f"User: {d['q'][:500]}\nAssistant: {d['a'][:800]}")
        except Exception: pass
    return "\n".join(out)

def save_chat(q, a):
    CHAT_LOG.parent.mkdir(exist_ok=True)
    with CHAT_LOG.open("a") as f: f.write(json.dumps({"q": q, "a": a[:1500]}, ensure_ascii=False) + "\n")
    CHAT_LOG.write_text("\n".join(CHAT_LOG.read_text().splitlines()[-100:]) + "\n")

def progress_text(prefix="⚙️ در حال اجرا"):
    el = int(time.time() - START)
    head = f"{prefix}… مرحله {S['steps']} · {el // 60:02d}:{el % 60:02d}" + (" · ♾ /free" if FREE else "")
    return (head + (f"\n{S['retry_msg']}" if S["retry_msg"] else "") + (f"\n▸ {S['last']}" if S["last"] else ""))[:4000]

def progress_html():
    el = int(time.time() - START)
    head = esc(f"⚙️ در حال اجرا… مرحله {S['steps']} · {el // 60:02d}:{el % 60:02d}" + (" · ♾ /free" if FREE else ""))
    if S["retry_msg"]: head += "\n" + esc(S["retry_msg"])
    if S["kind"] == "bash": return f"{head}\n⚡ دستور:\n" + pre(S["body"], "bash")
    if S["kind"] == "code": return f"{head}\n{esc(S['label'])}\n" + pre(S["body"], S["lang"])
    return head + (f"\n▸ {esc(S['label'])}" if S["label"] else "")

def progress_loop():
    last = ""
    while not S["done"]:
        h = progress_html()
        if MID and h != last:
            r = tg("editMessageText", chat_id=CHAT, message_id=MID, text=h, parse_mode="HTML")
            if not r.get("ok"): tg("editMessageText", chat_id=CHAT, message_id=MID, text=progress_text())
            last = h
        time.sleep(4)

# ---------- /stop mailbox: the Worker creates a branch "stop-<id>"; we poll for it ----------
ETAGS = {}
def gh(method, path):
    h = {"Authorization": f"Bearer {GH_TOKEN_API}", "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "ai-box"}
    if method == "GET" and path in ETAGS: h["If-None-Match"] = ETAGS[path]   # 304 answers don't count against GitHub's rate limit
    rq = urllib.request.Request(f"{GH_API}{path}", method=method, headers=h)
    try:
        with urllib.request.urlopen(rq, timeout=15) as r:
            if method == "GET" and r.headers.get("ETag"): ETAGS[path] = r.headers["ETag"]
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e: return e.code, None
    except Exception: return 0, None

def stop_requested():
    if not (GH_TOKEN_API and GH_REPO): return False
    _, refs = gh("GET", f"/repos/{GH_REPO}/git/matching-refs/heads/stop-")
    ids = {REQ, MID} - {""}
    for r in refs or []:
        if r.get("ref", "").rsplit("stop-", 1)[-1] in ids:
            gh("DELETE", f"/repos/{GH_REPO}/git/refs/{r['ref'][5:]}")
            return True
    return False

def stop_loop(p):
    while p.poll() is None:
        time.sleep(STOP_POLL)
        if p.poll() is None and stop_requested():
            S["why"] = "stop"
            try: os.killpg(p.pid, signal.SIGKILL)
            except Exception: p.kill()
            return

def watchdog(p):
    while p.poll() is None:
        now = time.time()
        why = "hard" if now - START > HARD else "idle" if now - S["beat"] > IDLE else ""
        if why:
            S["why"] = why
            try: os.killpg(p.pid, signal.SIGKILL)
            except Exception: p.kill()
            return
        time.sleep(5)

def handle(line):
    S["beat"] = time.time()
    try: d = json.loads(line)
    except Exception:
        RAW.append(line.strip())
        if RL_RE.search(line): S["rl"] = time.time()
        return
    t, part = d.get("type"), d.get("part") or {}
    if t == "error":
        if RL_RE.search(json.dumps(d)): S["rl"] = time.time()
        RAW.append(json.dumps(d, ensure_ascii=False)[:400]); return
    if t in ("step_start", "text", "tool_use", "step_finish"):
        S["prod"] = time.time()
        if S["retry_msg"].startswith("🔁"): S["retry_msg"] = ""
    if t == "step_start": STEP_TEXT.clear(); S["steps"] += 1
    elif t == "text":
        if (part.get("text") or "").strip(): STEP_TEXT.append(part["text"].strip())
    elif t == "tool_use":
        st = part.get("state") or {}; inp = st.get("input") or {}
        tool = part.get("tool") or ""; S["tools"] += 1
        if tool == "todowrite": plan_update(inp.get("todos")); return
        path = inp.get("filePath") or inp.get("path") or ""
        what = (inp.get("command") if tool == "bash" else None) or st.get("title") or path or inp.get("url") or inp.get("pattern") or tool
        S["last"] = f"{ICON.get(tool, '🛠')} {str(what).strip()}"[:1500]
        code = inp.get("content") if tool == "write" else inp.get("newString") if tool == "edit" else None
        if tool == "bash" and inp.get("command"):
            S.update(kind="bash", body=str(inp["command"]).strip()[:1500], label="", lang="bash")
        elif code:
            S.update(kind="code", label=f"{ICON.get(tool)} {path}", body=str(code)[:900] + ("\n…" if len(str(code)) > 900 else ""), lang=lang_for(path))
        else:
            S.update(kind="text", label=S["last"][:300], body="", lang="")
    elif t == "step_finish":
        if STEP_TEXT: S["final"] = "\n\n".join(STEP_TEXT)

def tool_running(root, quiet):
    """True if opencode has a descendant that is a shell or was started during the current silence (= a tool is running)."""
    out = subprocess.run(["ps", "-eo", "pid=,ppid=,etimes=,comm="], capture_output=True, text=True).stdout
    rows, kids = {}, {}
    for l in out.splitlines():
        parts = l.split(None, 3)
        if len(parts) == 4 and parts[2].isdigit(): rows[parts[0]] = (int(parts[2]), parts[3].strip()); kids.setdefault(parts[1], []).append(parts[0])
    todo, seen = [str(root)], set()
    while todo:
        for k in kids.get(todo.pop(), []):
            if k in seen: continue
            seen.add(k); todo.append(k)
            et, comm = rows[k]
            if comm in ("bash", "sh", "dash", "zsh") or et <= quiet + 2: return True
    return False

def stall_loop(p):
    """429 message seen and nothing productive since, or the model call is silent for too long: kill so main() can retry."""
    while p.poll() is None:
        time.sleep(2)
        now = time.time(); quiet = now - S["beat"]
        rl = S["rl"] > S["prod"] and now - S["rl"] > RL_GRACE and quiet > RL_GRACE
        stalled = quiet > MODEL_STALL and not tool_running(p.pid, quiet)
        if (rl or stalled) and not S["why"] and p.poll() is None:
            S["retry"] = "rate-limit" if rl else "stalled"
            try: os.killpg(p.pid, signal.SIGKILL)
            except Exception: p.kill()
            return

def retry_reason(code):
    if S["why"]: return ""
    if S["retry"]: return S["retry"]
    if code != 0 and S["rl"] and S["rl"] >= S["prod"]: return "rate-limit"
    return ""

def backoff_wait(wait, attempt, reason):
    what = "محدودیت سرعت مدل (429)" if reason == "rate-limit" else "پاسخ نیامدن مدل"
    end = time.time() + wait
    while time.time() < end:
        if time.time() - START > HARD: S["why"] = "hard"; return False
        if stop_requested(): S["why"] = "stop"; return False
        S["retry_msg"] = f"⏳ {what}؛ تلاش مجدد {attempt}/{MAX_RETRY} تا {int(end - time.time())} ثانیه‌ی دیگر…"
        time.sleep(min(8, max(0.5, end - time.time())))
    S["retry_msg"] = f"🔁 تلاش مجدد {attempt}/{MAX_RETRY}…"
    return True

def run_agent(prompt, resume):
    cmd = ["opencode", "run", "--format", "json", "--auto"] + (["--continue"] if resume else []) + (["-m", MODEL] if MODEL else []) + [prompt]
    env = dict(os.environ, OPENCODE_ENABLE_EXA="1", OPENCODE_DISABLE_AUTOUPDATE="1")
    S.update(beat=time.time(), prod=time.time(), rl=0.0, retry="")
    try:
        p = subprocess.Popen(cmd, cwd=WS, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1, start_new_session=True)
    except FileNotFoundError:
        S["nobin"] = True; return 127
    for fn in (watchdog, stop_loop, stall_loop): threading.Thread(target=fn, args=(p,), daemon=True).start()
    for line in p.stdout:
        if not QUIET: print(line.rstrip(), flush=True)
        handle(line)
    return p.wait()

SENT, SEEN, LAST_SEND, SHIP_LOCK = {}, {}, {}, threading.Lock()
MAX_SENDS = 30

def send_one(p):
    ext, size = p.suffix.lower(), p.stat().st_size
    el = int(time.time() - START)
    if size > 49_000_000:
        tg("sendMessage", chat_id=CHAT, text=f"📦 {p.name} بزرگ‌تر از حد تلگرام است؛ در پوشه‌ی outputs ریپو ذخیره می‌شود."); return
    method, field = ("sendVideo", "video") if ext in (".mp4", ".mov", ".webm") else ("sendPhoto", "photo") if ext in (".png", ".jpg", ".jpeg") and size < 9_000_000 else ("sendDocument", "document")
    cmd = ["curl", "-s", "-o", "/dev/null", "-F", f"chat_id={CHAT}", "-F", f"caption=📎 {p.name} · {el // 60:02d}:{el % 60:02d}", "-F", f"{field}=@{p}"]
    if reply_json(): cmd += ["-F", f"reply_parameters={reply_json()}"]
    if field == "video": cmd += ["-F", "supports_streaming=true"]
    subprocess.run(cmd + [f"https://api.telegram.org/bot{TG}/{method}"])

def ship(final=False):
    """Send new/changed files from outputs/ as replies to the request. While running: only files unchanged for 6s, re-send at most once per 60s."""
    with SHIP_LOCK:
        if not OUT.exists(): return
        for p in sorted(OUT.rglob("*")):
            try:
                if not p.is_file() or p.name.startswith(".") or p.name == "README.md" or p.suffix in (".tmp", ".part", ".lock"): continue
                st = p.stat()
                if st.st_size == 0 or st.st_mtime < START - 2: continue
                sig = (st.st_size, st.st_mtime_ns)
                if SENT.get(p) == sig: continue
                if not final:
                    prev = SEEN.get(p)
                    if prev is None or prev[0] != sig: SEEN[p] = (sig, time.time()); continue
                    if time.time() - prev[1] < 6 or time.time() - LAST_SEND.get(p, 0) < 60: continue
                if len(LAST_SEND) >= MAX_SENDS and p not in LAST_SEND: continue
                send_one(p); SENT[p] = sig; LAST_SEND[p] = time.time()
            except OSError: pass

def ship_loop():
    while not S["done"]:
        ship(); time.sleep(3)

def main():
    global MSG, FREE, HARD, IDLE
    Path("/tmp/run_started").write_text("1")   # lets the workflow know the script started (vs. crashed on load)
    m = MSG.strip()
    if m.lower().startswith("/free"):
        FREE, MSG, HARD, IDLE = True, m[5:].strip(), 21000, 3600   # up to 5h50m; GitHub's own max is 6h
        if not MSG:
            tg("editMessageText" if MID else "sendMessage", chat_id=CHAT, **({"message_id": MID} if MID else {}), text="بعد از /free درخواستتان را هم بنویسید. مثال: /free یک ویدیوی ۳۰ ثانیه‌ای بساز")
            return 0
    if MSG.strip() == "/reset":
        CHAT_LOG.unlink(missing_ok=True)
        tg("editMessageText" if MID else "sendMessage", chat_id=CHAT, **({"message_id": MID} if MID else {}), text="حافظه‌ی گفتگو پاک شد.")
        return 0
    c = CTX.strip() or load_ctx()
    ctx = f"Conversation so far (for context only):\n{c}\n\n" if c else ""
    hint = "NO strict time limit for this task (up to about 5 hours). Take the time to make it excellent; still keep single shell commands under ~10 minutes (run long jobs in the background and poll).\n\n" if FREE else ""
    prompt = f"{hint}{ctx}Request from the user (via Telegram):\n{MSG}"
    threading.Thread(target=progress_loop, daemon=True).start()
    threading.Thread(target=ship_loop, daemon=True).start()
    code, attempt = run_agent(prompt, False), 0
    if S["nobin"]:
        S["done"] = True; tg("sendMessage", chat_id=CHAT, text="❌ opencode نصب نشده."); return 127
    while True:
        reason = retry_reason(code)
        if not reason: break
        if attempt >= MAX_RETRY: S["why"] = "rl" if reason == "rate-limit" else "stalled"; break
        attempt += 1
        if not backoff_wait(BACKOFFS[min(attempt - 1, len(BACKOFFS) - 1)], attempt, reason): break
        S["retries"] = attempt
        code = run_agent(prompt + RESUME_NOTE, True)
    S["done"] = True; time.sleep(0.2)
    el = int(time.time() - START)
    if MID: tg("editMessageText", chat_id=CHAT, message_id=MID, text=("⛔ متوقف شد" if S["why"] == "stop" else "✅ تمام شد" if code == 0 else "❌ با خطا تمام شد") + f" ({S['steps']} مرحله · {S['tools']} ابزار" + (f" · {S['retries']} تلاش مجدد" if S["retries"] else "") + f" · {el // 60:02d}:{el % 60:02d})")
    note = {"rl": "⚠️ بعد از چند تلاش مجدد هم محدودیت سرعت مدل (429) برقرار بود. چند دقیقه بعد دوباره بفرستید (اگر تکرار شد، سقف رایگان تمام شده است).", "stalled": "⚠️ مدل بعد از چند تلاش مجدد هم جواب نداد.", "stop": "⛔ به درخواست شما متوقف شد.", "hard": f"⏱ به سقف زمان ({HARD // 60} دقیقه) رسید و متوقف شد.", "idle": f"⏱ {IDLE // 60} دقیقه هیچ فعالیتی نبود (گیر کرده بود) و متوقف شد."}.get(S["why"], "")
    final = S["final"] or "\n\n".join(STEP_TEXT) or ("(پاسخ متنی دریافت نشد)\n" + "\n".join(RAW[-12:]))
    if note: final = note + "\nفایل‌هایی که تا اینجا ساخته شده بود پایین ضمیمه است.\n\n" + final
    plan_send("⛔ متوقف شد" if S["why"] == "stop" else f"🏁 پایان · {mmss(el)}" if code == 0 else "❌ با خطا تمام شد")
    send_md(final)
    ship(final=True)
    if code == 0 and S["final"]: save_chat(MSG, S["final"])
    return 0 if S["why"] == "stop" else code

if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as e:   # unexpected crash: tell the user instead of leaving "در حال انجام" forever
        tg("editMessageText" if MID else "sendMessage", chat_id=CHAT, **({"message_id": MID} if MID else {}), text=f"❌ خطای داخلی در برنامه: {type(e).__name__}: {str(e)[:300]}")
        raise
