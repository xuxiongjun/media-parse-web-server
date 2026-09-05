import os
import json
from pathlib import Path

data = json.loads(Path(os.environ["TEMP"], "dy_detail.json").read_text(encoding="utf-8"))
detail = data.get("aweme_detail") or {}
print("desc", (detail.get("desc") or "")[:80])
author = (detail.get("author") or {}).get("nickname")
print("author", author)
video = detail.get("video") or {}
print("video keys", list(video.keys())[:40])
for key in ["play_addr", "download_addr", "play_addr_h264", "play_addr_265", "bit_rate"]:
    node = video.get(key)
    if not node:
        print(key, None)
        continue
    if key == "bit_rate" and isinstance(node, list):
        print("bit_rate count", len(node))
        for br in node[:3]:
            pa = (br.get("play_addr") or {})
            urls = pa.get("url_list") or []
            print(" br", br.get("gear_name"), br.get("quality_type"), urls[:1])
    else:
        urls = node.get("url_list") if isinstance(node, dict) else None
        print(key, "uri", node.get("uri") if isinstance(node, dict) else None, "urls", (urls or [])[:2])

cover = ((video.get("origin_cover") or {}).get("url_list") or [None])[0]
print("cover", cover)
print("duration", video.get("duration"), detail.get("duration"))
