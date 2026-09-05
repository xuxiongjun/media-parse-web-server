import re
import json
from pathlib import Path

text = Path.home().joinpath("AppData/Local/Temp/dy.html")
# fallback for TEMP
import os
p = Path(os.environ.get("TEMP", ".")) / "dy.html"
text = p.read_text(encoding="utf-8", errors="ignore")
print("html len", len(text))

m = re.search(r"window\._ROUTER_DATA\s*=\s*(\{.*?\})</script>", text, re.S)
print("matched", bool(m))
if not m:
    # try looser
    idx = text.find("window._ROUTER_DATA")
    print("idx", idx)
    print(text[idx:idx+200] if idx>=0 else text[:300])
    raise SystemExit(1)

raw = m.group(1)
data = json.loads(raw)
(Path(os.environ["TEMP"]) / "dy.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

def walk(o, path=""):
    if isinstance(o, dict):
        keys = set(o.keys())
        if keys & {"play_addr", "download_addr", "playApi", "play_api", "url_list", "bit_rate", "video"}:
            interesting = {k: o.get(k) for k in ["play_addr", "download_addr", "playApi", "play_api", "url_list", "uri", "url", "bit_rate"] if k in o}
            if interesting:
                print("FOUND", path, json.dumps(interesting, ensure_ascii=False)[:400])
        for k, v in o.items():
            walk(v, f"{path}.{k}")
    elif isinstance(o, list):
        for i, v in enumerate(o[:80]):
            walk(v, f"{path}[{i}]")

walk(data)

# also search string urls
s = json.dumps(data)
for pat in [r"https:[^\"\\]+mp4[^\"\\]*", r"https:[^\"\\]+play[^\"\\]*", r"https:[^\"\\]+video[^\"\\]*"]:
    found = re.findall(pat, s)
    print(pat, "count", len(found))
    for u in found[:8]:
        print(" ", u.replace("\\u002F", "/").replace("\\/", "/")[:200])
