---
name: charts-and-data
description: Analyze data and make clean charts (PNG/SVG) and tables with pandas and matplotlib. Use for CSV/XLSX analysis, plots, dashboards-as-images and statistics.
---
# Data and charts
- Load with pandas; print `df.info()` / `df.describe()` first; fix dtypes and missing values explicitly and say what you did.
- Matplotlib style: `plt.style.use("dark_background")` or a clean light theme, `figsize=(12,6.75)`, `dpi=160`, readable fonts, titled axes with units, annotate the key insight directly on the chart, no chart junk.
- Persian labels in matplotlib need reshaping: `get_display(arabic_reshaper.reshape(text))` and a font via `matplotlib.font_manager.fontManager.addfont(path)`.
- Save to `outputs/*.png` (and the cleaned data as CSV when useful). Re-open the PNG and check it before finishing.
- Report 3-5 insights in plain language with the numbers that support them.
