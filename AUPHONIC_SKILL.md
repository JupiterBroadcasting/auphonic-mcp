---
name: auphonic
description: Upload, process, and manage podcast audio through Auphonic. Use this skill whenever the user mentions uploading audio, processing a podcast episode, checking production status, Auphonic presets, SCALE recordings, LUP, Launch, or any audio processing workflow. Always activate for phrases like "process this audio", "upload to Auphonic", "check if it's done", or "what presets do we have".
---

# Auphonic Audio Processing

You have access to the Auphonic MCP server via mcp-injector. Tools are called using the
`mcp__auphonic__<tool>` naming convention — NOT as regular function calls.

## How mcp-injector tools work

The tool directory is injected into your system prompt as text. To call a tool:

1. Use the name exactly as listed: `mcp__auphonic__upload_audio`
2. Pass arguments as a map: `{:show "lup" :type "main" :file_path "/path/to/file.flac"}`
3. If you get an "Unknown tool" error, first call `get_tool_schema {:tool "mcp__auphonic__upload_audio"}` to resolve the schema, then retry.

## Available tools

- `mcp__auphonic__upload_audio` — Upload audio file and start processing
- `mcp__auphonic__check_status` — Check processing status (one-shot, must poll)
- `mcp__auphonic__list_productions` — List productions, optionally filter by status
- `mcp__auphonic__download_output` — Download processed file to local path (**see warning below**)
- `mcp__auphonic__delete_production` — Delete a production
- `mcp__auphonic__list_presets` — List all available presets with UUIDs
- `mcp__auphonic__start_multitrack_production` — Build a multitrack production from stems
  already living on an external service (**no upload**; see the multitrack section)
- `mcp__auphonic__start_production` — Start a previously saved production

## Status codes

`check_status` returns a numeric `status` **and** a `status_string`.

**Trust `status_string`, not a hand-maintained code table** — `check_status` prints it verbatim.
The API's enum, for orientation:

| code | meaning | | code | meaning |
|---|---|---|---|---|
| 0 | File Upload | | 5 | Encoding |
| 1 | Waiting | | 9 | Incomplete |
| 2 | **Error** | | 11 | Outdated |
| 3 | **Done** ✓ | | 98 | Empty Production |
| 4 | Audio Processing | | | |

Polling rules:

- Stop on `3` (done) or `2` (error).
- `9`, `11`, `98` are **effectively terminal** — they will never reach 3. A loop that only
  watches for 2 or 3 spins forever. Treat them as stop-and-report.
- Everything else (4, 5, …) is still working. Code `4` is common and is *not* an error.
- On any stop, report the full response to the user.

## Shows and valid types

- `lup` → valid types: `bootleg`, `adfree`, `main`
- `launch` → valid types: `bootleg`, `main`

## Presets — CRITICAL RULES

**Always pass a UUID, never a preset name string.**

Known presets (as of March 2026):
- `clipper-default`: `eEWnjTADFLzSZco4HhgJ4W` — default for clip processing
- `Loundness Norm Voice AutoEQ FLAC`: `8ATd9n6gZW9ao8uJKvt8kk` — use for SCALE recordings

If no `preset` is specified by the user, omit the field entirely — the server will fall back to
the `AUPHONIC_PRESET_<SHOW>` environment variable for that show.

If the user mentions a preset by name (not UUID), call `mcp__auphonic__list_presets` first to
look up the UUID, then use that UUID in `upload_audio`.

## Standard upload workflow

### Step 1 — Upload
```
mcp__auphonic__upload_audio {
  :show "lup"          ; or "launch"
  :type "main"         ; must match show's valid types
  :file_path "/absolute/path/to/file.flac"
  :title "Episode Title"          ; optional
  :preset "eEWnjTADFLzSZco4HhgJ4W"  ; optional — omit to use show default
}
```
Response includes `production_uuid` — save this for all subsequent calls.

### Step 2 — Poll until done
```
mcp__auphonic__check_status {:production_uuid "<uuid>"}
```
Repeat until `status = 3`. Tell the user what the current status is each time.
If `status = 2` (Error), report the full response to the user and stop.

### Step 3 — Completion
When status = 3, report to the user:
- Production UUID
- Link: `https://auphonic.com/production/<uuid>`
- Output formats available (from the status response)
- Suggest they download via the Auphonic web interface

## Multitrack production from service stems (no upload)

Use when the stems already live on an external service (WebDAV share) and Auphonic should pull
them itself — the normal weekly LUP shape. Fixture: episode 690, 4 stems, preset `TRANSCRIBE-LUP`.

### CRITICAL: the preset ships a stale `output_basename`

```
preset TRANSCRIBE-LUP (QE2j87HqCRXsN5C4k2yDKM)
  output_basename = 'lup-0598'    <-- whatever episode the preset was born on
  metadata.title  = ''
  multi_input_files = Chris, Wes, Brent, Mumble
```

Auphonic's `output_basename` is a **plain static field with no `{{episode}}` placeholder**. If you
omit `basename`, the production inherits `lup-0598` and you get `lup-0598.vtt / .html / .json` —
wrong episode, silently. The empty `metadata.title` fails *safe*; the basename fails **unsafe**.

> **Always pass `basename` and `title` explicitly.** e.g. `basename "lup-0690"`,
> `title "Linux Unplugged 690"`.

And **verify them before starting** — neither the save output nor `check_status` prints them.
Read the `auphonic://production/{uuid}` resource (or the production JSON) to confirm.

### CRITICAL: never pass `multi_input_files` at creation

Passing `multi_input_files` to `/productions.json` **appends to the preset's template** rather than
replacing it — observed 4→8 and 8→12, i.e. duplicate and empty track ids. Auphonic also hard-caps
at 8 tracks (HTTP 400, "We do not allow more than 8 tracks in our web service!").

The server therefore: creates from the preset with no `multi_input_files` → fills each preset track
**by id** → removes unsupplied preset tracks → appends preset-lacking tracks → reads the list back.

### The run: four calls, never save twice

```
1. list_productions          ; no orphan production from a prior attempt?
2. start_multitrack_production  {preset, service, episode, ...}          ; dry run
                             ; -> prints the DERIVED track map, prove it before creating
3. start_multitrack_production  {action "save", basename, title, ...}    ; creates
4. verify (see hard stops)   ; read auphonic://production/{uuid}
5. start_production          ; only after step 4 passes
6. poll check_status         ; until 3 / 2 / 9 / 11 / 98
```

`dry_run` creates **nothing**; `action "save"` **creates** a production. They are different steps —
running `save` twice leaves an orphan production behind.

### Passing the service

Pass the service by **display name** (`"LUP Stems"`), not its UUID. Only the display-name branch
runs the incoming/publishing health check; a UUID bypasses it.

### Hard stops — do NOT call `start_production` if any of these is true

- track count ≠ the expected number
- any track shows `(no file)`
- `Tracks stored: 0` — a failed read-back prints this **with no warning** (the `via-count` check is
  guarded by `(seq actual)`). An empty result means "we couldn't read it", not "it has no tracks".
- `output_basename` or `title` in the saved production is not what you asked for

### Proving the stems are complete

`ls.json` returns **names only, no sizes** — a stem still being written to the share resolves
perfectly and Auphonic then pulls a truncated FLAC. The MCP cannot detect this. Get sizes from
rclone (`rclone lsf --format ps`) and confirm they are stable across two checks before starting.

### Getting a reason out of a failed call

`auphonic-request` (used by `start_production`, `check_status`, deletes) returns only
`"API error: HTTP <n>"` — **it discards the response body**, so a start failure gives no reason.
`auphonic-json-post` (create/attach/append) *does* keep `error_message`, so creation failures read
cleanly. If a start fails, fall back to a direct `curl` with the API key to read the body — most
likely causes are insufficient credits or a validation error.

### Rate limits

MCP's own limit is 60/min with a proper `Retry-After`. Polling at ≤12/min (every 5s+) is safe.
There is no 429 retry anywhere in the server.

## Download tool warning

`mcp__auphonic__download_output` performs a synchronous streaming file copy that can hang or
time out on large audio files. **Only use it if the user explicitly requests a local download
AND the file is small (e.g. a short clip).** For normal episode files, always direct the user
to download from `https://auphonic.com/production/<uuid>` instead.

## SCALE recordings

For audio from SCALE conference recordings, use preset UUID `8ATd9n6gZW9ao8uJKvt8kk`
(Loundness Norm Voice AutoEQ FLAC) unless the user specifies otherwise.

```
mcp__auphonic__upload_audio {
  :show "lup"
  :type "main"
  :file_path "/path/to/scale-recording.flac"
  :preset "8ATd9n6gZW9ao8uJKvt8kk"
  :title "SCALE 2026 - <topic>"
}
```

## Iteration budget

mcp-injector allows up to 50 tool-call iterations per request. A full upload+poll+complete
workflow uses approximately 3–8 iterations. You have plenty of headroom to poll multiple times.

## Example: user says "process this SCALE recording at /home/wes/recordings/scale.flac for LUP main"

1. Call `mcp__auphonic__upload_audio` with show=lup, type=main, file_path as given, preset=8ATd9n6gZW9ao8uJKvt8kk
2. Save the returned production_uuid
3. Poll `mcp__auphonic__check_status` until status=3
4. Report completion with link to Auphonic web interface
