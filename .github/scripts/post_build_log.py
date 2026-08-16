import json
import os
import urllib.request

with open("build_output_tail.txt", "r", errors="replace") as f:
    tail = f.read()

body = "Build failure log (tail):\n\n```\n" + tail + "\n```"
payload = json.dumps({"body": body}).encode("utf-8")

repo = os.environ["REPO"]
sha = os.environ["SHA"]
token = os.environ["GH_TOKEN"]

url = f"https://api.github.com/repos/{repo}/commits/{sha}/comments"
req = urllib.request.Request(url, data=payload, method="POST")
req.add_header("Authorization", f"token {token}")
req.add_header("Content-Type", "application/json")
req.add_header("Accept", "application/vnd.github+json")

with urllib.request.urlopen(req) as resp:
    print(resp.status)
