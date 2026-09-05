import os
import re
import json
from pathlib import Path
from urllib.request import Request, urlopen

ID = "7679053888742286582"
UA_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

urls = [
    (f"https://www.douyin.com/video/{ID}", UA_PC),
    (f"https://www.douyin.com/share/video/{ID}", UA_MOBILE),
    (f"https://m.douyin.com/share/video/{ID}", UA_MOBILE),
    (f"https://www.iesdouyin.com/aweme/v1/web/aweme/detail/?aweme_id={ID}&aid=1128&version_name=23.5.0&device_platform=android&os_version=2333", UA_MOBILE),
    (f"https://www.douyin.com/aweme/v1/web/aweme/detail/?device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id={ID}&pc_client_type=1&version_code=190500&version_name=19.5.0&cookie_enabled=true&screen_width=1920&screen_height=1080&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=122.0.0.0&browser_online=true&engine_name=Blink&engine_version=122.0.0.0&os_name=Windows&os_version=10&cpu_core_num=8&device_memory=8&platform=PC&downlink=10&effective_type=4g&round_trip_time=50", UA_PC),
    (f"https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids={ID}", UA_MOBILE),
]

out = Path(os.environ["TEMP"]) / "dy_probe"
out.mkdir(exist_ok=True)

for i, (url, ua) in enumerate(urls):
    print("\n====", i, url[:100])
    try:
        req = Request(url, headers={
            "User-Agent": ua,
            "Referer": "https://www.douyin.com/",
            "Accept": "text/html,application/json,*/*",
            "Accept-Language": "zh-CN,zh;q=0.9",
        })
        with urlopen(req, timeout=25) as resp:
            body = resp.read()
            ctype = resp.headers.get("Content-Type", "")
            final = resp.geturl()
        print("status ok", "ctype", ctype, "len", len(body), "final", final[:120])
        text = body.decode("utf-8", "ignore")
        (out / f"{i}.txt").write_text(text[:200000], encoding="utf-8")
        for key in ["play_addr", "playApi", "play_api", "download_addr", "bit_rate", "mp4", "_ROUTER_DATA", "RENDER_DATA", "UNIVERSAL_DATA", "aweme_detail", "videoData"]:
            if key in text:
                print(" contains", key)
        # extract first few http urls with video-ish
        found = re.findall(r"https:[^\"'\\]+(?:mp4|play|video_id|xhscdn|byte)[^\"'\\]*", text)
        print(" videoish urls", len(found))
        for u in found[:5]:
            print(" ", u.replace("\\u002F", "/")[:180])
    except Exception as e:
        print("ERR", type(e).__name__, e)
