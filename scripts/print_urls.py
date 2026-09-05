import os
import json
from pathlib import Path

d = json.loads(Path(os.environ["TEMP"], "dy_detail.json").read_text(encoding="utf-8"))
v = d["aweme_detail"]["video"]
for k in ["play_addr", "download_addr", "play_addr_h264"]:
    n = v.get(k) or {}
    urls = n.get("url_list") or []
    u0 = urls[0] if urls else None
    print(k, "uri=", n.get("uri"), "url=", (u0[:160] if u0 else None))
print("bitrate", len(v.get("bit_rate") or []))
br = (v.get("bit_rate") or [{}])[0]
u = ((br.get("play_addr") or {}).get("url_list") or [""])[0]
print("br0", br.get("gear_name"), u[:160] if u else None)
print("dur", v.get("duration"))
c = ((v.get("origin_cover") or {}).get("url_list") or [""])[0]
print("cover", c[:120] if c else None)
