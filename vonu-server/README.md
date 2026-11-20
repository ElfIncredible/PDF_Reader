# Vonu server

A small Python service that gives the Vonu PDF app the features a phone can't do well:

| Endpoint | What it does |
|---|---|
| `GET /health` | Status and which features are available (the app's **Test connection** button) |
| `POST /summarize` | Summary + key points with page citations. Long documents are summarised part by part, then combined |
| `POST /ask` | Answers a question from the document only, citing pages (`[p. 3]`). BM25 retrieval, no vector DB |
| `POST /tables` | Finds tables (pdfplumber) → JSON, or `?format=xlsx` for an Excel workbook (numbers stay numbers) |
| `POST /convert` | Word / Excel / PowerPoint / ODF / RTF / HTML / TXT → PDF (LibreOffice) |
| `POST /pdfa` | PDF → PDF/A-1b/2b/3b (Ghostscript, embedded sRGB profile); validated with veraPDF if installed (`X-Vonu-Validation` header) |
| `POST /transcribe` | **Vonu Minutes:** meeting recording → text with timed segments (faster-whisper, on this machine; `?language=en` optional). The audio is deleted right after |
| `POST /minutes/summary` | **Vonu Minutes:** `{title, text}` (the minutes as Markdown, then any transcripts) → overview + key points (decisions, actions with owners). Uses the AI model below; nothing is stored |

The app only sends **page text** for summaries and questions, and the **file** for tables,
conversion and PDF/A. Nothing is stored: files go to a temporary folder that's deleted after
each request.

## Run it

**With Docker (recommended):**
```bash
cd vonu-server
cp .env.example .env              # then set VONU_TOKEN to a long random value
docker compose up -d --build
docker compose exec ollama ollama pull llama3.1:8b   # first time only (~5 GB)
curl http://localhost:8000/health
```

**With Claude instead of Ollama** (used for Vonu Minutes AI summaries): in `.env` set
`LLM_PROVIDER=anthropic`, `LLM_MODEL=claude-opus-5-5`, `LLM_API_KEY=<your Anthropic key>` and
`MAX_CONTEXT_CHARS=400000`, then `docker compose up -d --build`. The key stays on this server;
the text being summarised is sent to Anthropic and billed per use. Skip the `ollama pull`.

**Without Docker (for development):**
```bash
python -m venv .venv && source .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
# Optional: LibreOffice and Ghostscript on your PATH for /convert and /pdfa
set -a; source .env; set +a                            # or set the variables another way
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
pytest -q
```

## Connect the app
1. Phone and computer on the same Wi-Fi. Find the computer's IP (`ipconfig` on Windows), e.g. `192.168.1.20`.
2. Vonu → Settings → **Vonu server** → address `http://192.168.1.20:8000`, token = your `VONU_TOKEN` → **Test connection**.
3. Allow the app to send documents to your server (the switch below), then open a PDF → ⋮ → **AI assistant**.

Outside your home network, put the server behind HTTPS (e.g. Caddy or a Cloudflare Tunnel) and use the `https://` address.

## Choosing a model
- **Ollama** (default): private and free. `llama3.1:8b` needs ~8 GB RAM; on a laptop without a GPU an answer
  takes 10–60 s. `qwen2.5:7b` and `mistral` also work well. Small models (≤3B) are fast but miss details.
- **Hosted APIs** (OpenAI-compatible or Anthropic): fast and strong, but document text is sent to that provider.
  Check their terms before using sensitive documents.

## Code
```
app/main.py     HTTP routes, auth, error mapping
app/rag.py      chunking, BM25 retrieval, prompts, summary/answer logic   ← most of the "AI"
app/llm.py      provider adapters (Claude via the anthropic SDK; others stdlib only)
app/tables.py   pdfplumber → rows → Excel
app/transcribe.py  faster-whisper speech to text for Vonu Minutes
app/minutes.py     AI summaries of Vonu Minutes meetings
app/convert.py  LibreOffice headless
app/pdfa.py     Ghostscript PDF/A + optional veraPDF
tests/          pytest (the LLM is faked; LibreOffice tests run if it's installed)
```
