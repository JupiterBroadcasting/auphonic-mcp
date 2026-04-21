# AGENTS.md — auphonic-mcp

> "Simple is the opposite of complex. Easy is the opposite of hard."
> — Rich Hickey

## What This Is

A Babashka Streamable HTTP MCP server wrapping the Auphonic API. It provides tools for uploading audio, checking production status, listing productions/presets, and downloading processed results.

**Transport:** MCP Streamable HTTP (spec `2025-03-26`)
**Endpoint:** Single `/mcp` POST + `/health`
**Compatible with:** mcp-injector `{:auphonic {:url "http://127.0.0.1:PORT/mcp"}}`

## Philosophy: Grumpy Pragmatism

We follow the same principles as `mcp-injector` and `art19-mcp`:

- **Actions, Calculations, Data** — Tool functions are actions; keep logic pure where possible.
- **One File is Fine** — The server lives in `auphonic-mcp-server.clj`. Don't split until complexity demands it.
- **Test-First Design** — (In progress) Use real in-process servers for integration tests.
- **YAGNI** — Don't build for imagined futures. Abstractions are a cost.

### Loves to Ship Pragmatism

Balance high quality bars with the absolute necessity of delivering functional software. We raise the bar on code, but we don't let "perfect" be the enemy of "shipped." Prioritize reliability and observability while maintaining high velocity.

## Learning: Simple vs. Easy (Hickey)

- **Simple** = coherent, understandable, non-intertwined components that promote reliability
- **Easy** = familiar, proximity, convenient short-term choices that often lead to complexity
- Choose simplicity deliberately through design and architecture

## Learning: Research-First (mcp-injector)

Before implementing:
1. Ask: "Does this already exist?"
2. Research ecosystem solutions
3. Specify with acceptance criteria and edge cases
4. Break into staged PRs

## Learning: Metamorphic Testing (Hillel Wayne)

Test properties and relationships rather than exact outputs:
- What invariants hold regardless of implementation?
- How does output change with input transformations?
- Verify correctness through relationships, not hardcoded expected values

## Learning: Data-Driven Simplicity (Eric Normand)

- Think in data structures and transformations
- Keep dependencies minimal and explicit
- Favor composable, functional approaches over imperative state
- Avoid conflating data, behavior, and state unnecessarily

## Review Sub-Agent Guidelines

### Grumpy Senior Reality Check
- What could go wrong in production that isn't covered?
- Have we tested edge cases or just the happy path?
- What's the simplest solution that actually works?
- What assumptions may not hold under load/real data?
- How hard is it to debug and fix at 3 AM?

### Loves to Ship Mindset
- Assume a hostile production environment but maintain delivery velocity.
- Prioritize rollback speed and observability to enable faster shipping.
- Focus on simple, functional solutions that can be iteratively improved.

### Review Checkpoints
- [ ] Simple vs. easy: Is this the simplest coherent solution?
- [ ] Research: Does existing tooling solve this?
- [ ] Properties: Can we verify correctness through invariants?
- [ ] Production: Monitoring, rollback, error paths covered?
- [ ] YAGNI: Are we building for hypothetical futures?
- [ ] Data: Are transformations explicit and minimal?

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
- **Production Resilience**: Loves to ship mentality — assume production will be hostile, prioritize observability and rapid rollback.
- **Review Philosophy**: Grumpy senior perspective with data-driven, property-based testing approach.