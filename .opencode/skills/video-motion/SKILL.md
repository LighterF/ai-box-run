---
name: video-motion
description: Fast, high-quality motion-graphics and showreel videos (MP4) with Python frames piped into ffmpeg. Use for any animation, video, intro, reel or slideshow request.
---
# Video / motion graphics (fast pipeline)
Goal: 15 s at 1280x720, 24 fps = 360 frames, rendered in < 3 min.
1. **Design first** (write it in your plan): 4-6 scenes, one palette, 1-2 fonts, easing everywhere (ease-out-expo: `1-2**(-10*t)`), text reveals, parallax, glow.
2. **Render with Pillow + numpy**, pipe raw frames to ffmpeg (no PNG files on disk):
```python
import subprocess, numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
W,H,FPS,DUR=1280,720,24,15
ff=subprocess.Popen(["ffmpeg","-y","-f","rawvideo","-pix_fmt","rgb24","-s",f"{W}x{H}","-r",str(FPS),"-i","-",
  "-c:v","libx264","-pix_fmt","yuv420p","-preset","veryfast","-crf","20","outputs/video.mp4"],stdin=subprocess.PIPE)
for f in range(FPS*DUR):
    t=f/FPS
    img=Image.new("RGB",(W,H)); d=ImageDraw.Draw(img)
    # ... draw scene(t) ...
    ff.stdin.write(img.tobytes())
ff.stdin.close(); ff.wait()
```
3. **Speed rules**: never loop over pixels in Python; build gradients/noise with numpy once and reuse; blur/glow on a small canvas then upscale; cache fonts and static layers outside the loop; render a 24-frame preview first (`DUR=1`) and check one frame before the full run.
4. **Audio** (optional): `ffmpeg -i video.mp4 -i music.wav -shortest -c:v copy -c:a aac out.mp4`. Generate simple synth audio with numpy if no music is available.
5. **Persian text**: see skill `pdf-and-docs` (reshape + bidi + a real font such as Vazirmatn or Noto Naskh Arabic).
6. **Verify**: `ffprobe -v error -show_entries format=duration:stream=width,height,r_frame_rate outputs/video.mp4`, then extract frames at 0/5/10/14 s with `ffmpeg -ss T -i ... -frames:v 1` and check them. Keep the file < 45 MB (raise `-crf` if needed).
