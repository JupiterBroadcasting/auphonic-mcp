# NEXT.md - Auphonic MCP Server

## Status: MVP Complete - Ready for Test Deployment

### Recent Commits (on feat/flake)

```
6e4ca3f feat: add rate limiting for production hardening
496437f refactor: replace curl shell call with http-client multipart
06dc090 test: add upload validation tests with temp file
8b787d0 docs: add NEXT.md development tracking document
17e3f82 chore: update flake.nix
2a2e214 test: expand test suite from 7 to 13 tests
ff5993e fix: update-session! to accept variadic args for session updates
10f8d0c chore: delete obsolete Claude-specific documentation files
```

### Completed ✓

1. **Security Hardening**

   - Replaced `shell/sh curl` with `http/post multipart` - no shell injection risk
   - Platform-independent file uploads
   - Better error handling

1. **Rate Limiting** ✓

   - 60 requests per minute per session
   - Returns 429 with Retry-After header
   - Sliding window approach
   - Configurable limits (rate-limit-max-requests, rate-limit-window-ms)

1. **Test Suite (14 tests, all passing)**

   - Protocol lifecycle
   - Session management
   - Error scenarios
   - Resources (list, read)
   - Prompts
   - Tool validation
   - Rate limiting

______________________________________________________________________

## Test Deployment Ready

### What's Working

- MCP protocol compliance (2025-03-26)
- 6 MCP tools: upload_audio, check_status, list_productions, download_output, delete_production, list_presets
- 3 MCP resources: config, presets, production/{uuid}
- 3 MCP prompts: upload_and_process, analyze_production, check_recent_uploads
- Session management with UUIDs
- Input validation with clear error messages
- Rate limiting (60 req/min per session)
- Health endpoint

### Configuration Required

```bash
export AUPHONIC_API_KEY="your-api-key"
export AUPHONIC_PRESET_LUP="preset-uuid"
export AUPHONIC_PRESET_LAUNCH="preset-uuid"
```

______________________________________________________________________

## Test Suite (14 tests, all passing)

1. `test-protocol-lifecycle` - Initialize, tools, resources, validation
1. `test-health-check` - Health endpoint
1. `test-error-scenarios` - Missing Content-Type, session ID errors
1. `test-session-lifecycle` - Create, use, delete sessions
1. `test-resource-config` - Read config resource
1. `test-resources-list` - List all resources
1. `test-resources-read-presets` - Read presets resource
1. `test-prompts` - List/get prompts
1. `test-tool-validation` - Tool validation
1. `test-list-presets-structure` - Validate list_presets structure
1. `test-list-productions-structure` - Validate list_productions structure
1. `test-invalid-prompt-error` - Unknown prompt returns error
1. `test-invalid-resource-error` - Unknown resource returns error
1. `test-rate-limiting` - Rate limiting behavior

______________________________________________________________________

## Running Tests

```bash
bb test-runner.clj
```

______________________________________________________________________

## Notes

- Tests run on port 3002 to avoid conflicts with other MCP servers
- API key required for tests that make actual Auphonic API calls
- Server-side validation handles show-specific episode types
