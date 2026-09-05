import os
import re
from pathlib import Path

t = (Path(os.environ["TEMP"]) / "dy_probe" / "0.txt").read_text(encoding="utf-8", errors="ignore")
print("len", len(t))
for key in [
    "UNIVERSAL_DATA",
    "RENDER_DATA",
    "_ROUTER_DATA",
    "SIGI_STATE",
    "__NEXT_DATA__",
    "play_addr",
    "download_addr",
    "awemeDetail",
    "videoDetail",
]:
    print(key, t.find(key))

for m in re.finditer(r"<script[^>]+id=['\"]([^'\"]+)['\"]", t):
    print("script id", m.group(1))

for m in re.finditer(r"<script[^>]*>(\{.*?\})</script>", t, re.S):
    blob = m.group(1)
    if len(blob) > 800:
        print("blob", len(blob), blob[:120].replace("\n", " "))
