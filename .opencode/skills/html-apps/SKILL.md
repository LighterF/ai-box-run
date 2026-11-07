---
name: html-apps
description: Build and verify single-file HTML pages, tools, games and presentations, with screenshots via Playwright using the installed Chrome. Use for websites, landing pages, calculators, visualizations and slide decks.
---
# HTML apps
- One self-contained `outputs/name.html` (inline CSS/JS, no external network at runtime; fonts embedded or system stack). Responsive, dark/light aware, RTL when the content is Persian (`dir="rtl"`).
- Design with intent: one palette, a type scale, spacing system, real micro-interactions and easing `cubic-bezier(.16,1,.3,1)`.
- **Verify with a real browser** (no download needed, uses installed Chrome):
```python
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b=p.chromium.launch(channel="chrome",args=["--no-sandbox"]); pg=b.new_page(viewport={"width":1280,"height":800})
    errs=[]; pg.on("pageerror",lambda e:errs.append(str(e))); pg.on("console",lambda m: m.type=="error" and errs.append(m.text))
    pg.goto("file:///abs/outputs/name.html"); pg.wait_for_timeout(800); pg.screenshot(path="outputs/preview.png"); b.close()
print(errs)
```
  Fix every console error, check a mobile viewport (390x844) too, and click the main interactions.
- Deliver the HTML plus `preview.png`.
