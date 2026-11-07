---
name: web-research
description: Research current facts on the web with sources, cross-checking and a concise brief. Use for news, prices, versions, comparisons, documentation lookups and anything that may have changed.
---
# Web research
1. Break the question into 2-5 sub-questions. Search each separately with short, specific queries (1-6 words); add the current year for time-sensitive topics.
2. Fetch primary sources (official docs, vendor pages, filings) with `webfetch`; skip SEO listicles and forums unless they are the point.
3. Cross-check key numbers/dates across >= 2 sources. State disagreements explicitly.
4. For big topics use the `researcher` subagent in parallel per sub-topic.
5. Output: the answer first, then key facts with source URLs, then uncertainty. If asked for a deliverable, save it (Markdown/PDF) in `outputs/`.
6. Never invent URLs, quotes or numbers. Paraphrase; quote at most a short phrase.
