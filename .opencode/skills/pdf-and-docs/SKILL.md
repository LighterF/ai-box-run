---
name: pdf-and-docs
description: Create PDF, DOCX, XLSX and Markdown documents, including correct Persian/Arabic right-to-left text. Use for reports, resumes, letters, spreadsheets and any document deliverable.
---
# Documents (PDF / DOCX / XLSX)
- **PDF**: `reportlab` (full control) or `fpdf2`. For rich layout write HTML and print with Chrome: `google-chrome --headless=new --no-sandbox --print-to-pdf=outputs/x.pdf file:///abs/path.html` (best Persian shaping, free CSS layout).
- **DOCX**: `python-docx`; **XLSX**: `openpyxl` (formulas as strings, widths, number formats); **MD/HTML -> DOCX**: `pandoc in.md -o out.docx`.
- **Persian/Arabic text** (critical):
  1. Use a real font: try `https://github.com/google/fonts/raw/main/ofl/vazirmatn/Vazirmatn%5Bwght%5D.ttf` into `assets/fonts/`; fallback to Noto Naskh/Sans Arabic (`fc-list | grep -i arabic`).
  2. In reportlab/Pillow reshape and reorder: `import arabic_reshaper; from bidi.algorithm import get_display; t=get_display(arabic_reshaper.reshape(text))`. In HTML just use `dir="rtl" lang="fa"`.
  3. Digits: Persian digits via `str.translate(str.maketrans("0123456789","۰۱۲۳۴۵۶۷۸۹"))` when the user is writing Persian prose.
- **Verify**: `pdfinfo`, then `pdftoppm -r 60 -png outputs/x.pdf /tmp/p` and inspect pages for overflow, missing glyphs (empty boxes), reversed text.
- Keep layout clean: consistent margins, 1-2 fonts, visual hierarchy, page numbers on long documents.
