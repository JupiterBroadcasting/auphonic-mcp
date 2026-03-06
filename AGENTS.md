# AGENTS.md — auphonic-mcp

> "Simple is the opposite of complex. Easy is the opposite of hard."
> — Rich Hickey

## What This Is

A Babashka Streamable HTTP MCP server wrapping the Auphonic API. It provides tools for uploading audio, checking production status, listing productions/presets, and downloading processed results.

**Transport:** MCP Streamable HTTP (spec `2025-03-26`)
**Endpoint:** Single `/mcp` POST + `/health`
**Compatible with:** mcp-injector `{:auphonic {:url "http://127.0.0.1:PORT/mcp"}}`

## Project Structure

```
auphonic-mcp/
├── auphonic-mcp-server.clj  # Single-file MCP server (the whole thing)
├── flake.nix                # Nix package
├── README.md                # Project overview
├── AGENTS.md                # This file (philosophy & workflows)
├── docs/
│   └── AUPHONIC_API.md      # API documentation reference
└── tests/                   # (Planned) Integration tests
```

## Philosophy: Grumpy Pragmatism

We follow the same principles as `mcp-injector` and `art19-mcp`:

- **Actions, Calculations, Data** — Tool functions are actions; keep logic pure where possible.
- **One File is Fine** — The server lives in `auphonic-mcp-server.clj`. Don't split until complexity demands it.
- **Test-First Design** — (In progress) Use real in-process servers for integration tests.
- **YAGNI** — Don't build for imagined futures. Abstractions are a cost.

## Workflow

- **Test-driven** — Write tests that verify real client usage.
- **Integration tests only** — Use real HTTP calls against the server; avoid mocks where possible.
- **Clean lint** — Use `clj-kondo`. No warnings tolerated.
- **Formatting**:
  - Clojure/Babashka: `cljfmt fix <file>`
  - Markdown: `mdformat <file>`
  - Nix: `nix fmt`

## Running

```bash
# Start on default port 3003
bb auphonic-mcp-server.clj

# Start on specific port
bb auphonic-mcp-server.clj 3007
```

Auth via environment variable:
```bash
export AUPHONIC_API_KEY="your-api-token"
# Show-specific presets
export AUPHONIC_PRESET_LUP="preset-uuid"
export AUPHONIC_PRESET_LAUNCH="preset-uuid"
```

## Tools

| Tool | Description |
|------|-------------|
| `upload_audio` | Upload audio to Auphonic using show presets (lup, launch). |
| `check_status` | Check processing status of a production. |
| `list_productions` | List recent productions with status filters. |
| `download_output` | Download processed files to a local path. |
| `delete_production` | Remove a production from Auphonic. |
| `list_presets` | List all available presets in the account. |

## Resources & Prompts

- **Resources**: `auphonic://config`, `auphonic://presets`, `auphonic://production/{uuid}`
- **Prompts**: `upload_and_process`, `analyze_production`, `check_recent_uploads`

## Lessons Learned

- **Session Handling**: mcp-injector expects `Mcp-Session-Id` header. The server validates this session on every request after initialization.
- **Rate Limiting**: Simple in-memory rate limiting is implemented (60 requests/min).
- **Multipart Uploads**: Uses `babashka.http-client` with `io/file` for efficient audio uploads.
