# Auphonic MCP Server

A Model Context Protocol (MCP) server that provides Claude with Auphonic audio processing capabilities.

## Overview

This MCP server enables Claude to:
- Upload audio files to Auphonic for processing
- Check production status
- List and manage productions
- Download processed files
- Access presets and configuration

## Features

### Tools (6)
Claude can invoke these actions:

1. **upload_audio** - Upload audio file and start processing
2. **check_status** - Check production status by UUID
3. **list_productions** - List all productions with filtering
4. **download_output** - Download processed audio files
5. **delete_production** - Delete a production
6. **list_presets** - List available Auphonic presets

### Resources (3)
Claude can read these for context:

1. **auphonic://config** - API configuration and show settings
2. **auphonic://presets** - List of all presets
3. **auphonic://production/{uuid}** - Individual production details

### Prompts (3)
Users can trigger these workflows:

1. **upload_and_process** - Guide through uploading an audio file
2. **analyze_production** - Analyze production status and readiness
3. **check_recent_uploads** - Check recently uploaded files

## Installation

### Prerequisites

- Babashka (bb) installed
- Auphonic account with API key
- At least one Auphonic preset configured
- Claude Desktop (or another MCP client)

### Setup

1. **Install Babashka**
   ```bash
   # macOS
   brew install borkdude/brew/babashka
   
   # Linux
   curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
   chmod +x install
   ./install
   ```

2. **Get Auphonic Credentials**
   - API Key: https://auphonic.com/account
   - Preset UUIDs: https://auphonic.com/presets (copy UUID from URL)

3. **Make the server executable**
   ```bash
   chmod +x auphonic-mcp-server.clj
   ```

4. **Configure Claude Desktop**
   
   Edit your Claude Desktop config file:
   - macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
   - Linux: `~/.config/Claude/claude_desktop_config.json`
   
   Add this configuration:
   ```json
   {
     "mcpServers": {
       "auphonic": {
         "command": "bb",
         "args": [
           "/absolute/path/to/auphonic-mcp-server.clj",
           "stdio"
         ],
         "env": {
           "AUPHONIC_API_KEY": "your-api-key-here",
           "AUPHONIC_PRESET_LUP": "preset-uuid-for-lup",
           "AUPHONIC_PRESET_LAUNCH": "preset-uuid-for-launch"
         }
       }
     }
   }
   ```

5. **Restart Claude Desktop**

## Usage

### Example Conversations with Claude

**Upload an audio file:**
```
You: "Upload /path/to/lup-0653-bootleg.mp3 as a bootleg episode for LUP"

Claude will use the upload_audio tool and report back with the production UUID.
```

**Check status:**
```
You: "Check the status of production abc123xyz"

Claude will use check_status and tell you if it's done processing.
```

**Download when ready:**
```
You: "Download the processed file from production abc123xyz to ~/Downloads"

Claude will use download_output to get the file.
```

**Analyze a production:**
```
You: "Analyze production abc123xyz and tell me if it's ready to publish"

Claude will use the analyze_production prompt to provide a comprehensive analysis.
```

**List recent uploads:**
```
You: "What have I uploaded recently?"

Claude will use list_productions to show your recent files.
```

### Using Prompts

Prompts are pre-configured workflows you can trigger:

1. **From Claude Desktop**: Click the 🔌 icon and select a prompt
2. **From conversation**: Just ask Claude naturally and it will use the appropriate tools

## Configuration

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `AUPHONIC_API_KEY` | Yes | Your Auphonic API key |
| `AUPHONIC_PRESET_LUP` | Yes* | Preset UUID for LUP show |
| `AUPHONIC_PRESET_LAUNCH` | Yes* | Preset UUID for Launch show |

*Required if you want to upload files for that show

### Show Configuration

The server is pre-configured for two shows:

**LUP (Linux Unplugged)**
- Types: bootleg, adfree, main

**Launch**
- Types: bootleg, main

To add more shows, edit the `show-types` map in `auphonic-mcp-server.clj`.

## Architecture

### MCP Protocol Flow

```
Claude Desktop
    ↓ (STDIO)
Auphonic MCP Server
    ↓ (HTTPS)
Auphonic API
```

### File Structure

```
auphonic-mcp-server.clj
├── Configuration (API endpoints, show types)
├── State Management (track recent uploads)
├── Auphonic API Client (reusable functions)
├── Tool Implementations (6 tools)
├── Resource Handlers (3 resources)
├── Prompt Generators (3 prompts)
├── JSON-RPC Handler (MCP protocol)
└── STDIO Transport (communication layer)
```

### Transport Modes

**STDIO (Default/Recommended)**
- Used by Claude Desktop
- Communication via stdin/stdout
- Secure, local-only

**HTTP (Debug Mode)**
```bash
./auphonic-mcp-server.clj http 3000

# Test with curl:
curl -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/list",
    "params": {}
  }'
```

## Development

### Testing the Server

1. **Test with HTTP mode:**
   ```bash
   export AUPHONIC_API_KEY="your-key"
   export AUPHONIC_PRESET_LUP="preset-uuid"
   ./auphonic-mcp-server.clj http 3000
   ```

2. **Use MCP Inspector:**
   ```bash
   npx @modelcontextprotocol/inspector bb auphonic-mcp-server.clj stdio
   ```

3. **Manual STDIO testing:**
   ```bash
   echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | \
     bb auphonic-mcp-server.clj stdio
   ```

### Adding New Tools

1. Implement the tool function:
   ```clojure
   (defn tool-my-new-tool [{:keys [arg1 arg2]}]
     ;; Your implementation
     {:content [{:type "text" :text "Result"}]})
   ```

2. Add to `handle-tools-list`:
   ```clojure
   {:name "my_new_tool"
    :description "What it does"
    :inputSchema {:type "object"
                  :properties {:arg1 {:type "string"}}
                  :required ["arg1"]}}
   ```

3. Add to `handle-tools-call`:
   ```clojure
   "my_new_tool" (tool-my-new-tool arguments)
   ```

### Adding New Resources

1. Implement the resource function:
   ```clojure
   (defn resource-my-data []
     {:uri "auphonic://my-data"
      :mimeType "application/json"
      :text (json/generate-string {...})})
   ```

2. Add to `handle-resources-list` and `handle-resources-read`

### Adding New Prompts

1. Implement the prompt function:
   ```clojure
   (defn prompt-my-workflow [arg]
     {:messages [{:role "user"
                  :content {:type "text"
                           :text "Do something..."}}]})
   ```

2. Add to `handle-prompts-list` and `handle-prompts-get`

## Security

### API Key Storage
- ✅ Stored in Claude Desktop config (secure)
- ✅ Never hardcoded in the server
- ✅ Passed via environment variables only

### User Consent
- Claude Desktop prompts user before invoking ANY tool
- User sees tool name, arguments, and description
- User must approve each action

### Input Validation
- All file paths validated before use
- Show/type values validated against whitelist
- Production UUIDs validated by Auphonic API

### Best Practices
- Never log API keys
- Validate all inputs from Claude
- Use STDIO transport (local-only) for production
- HTTP mode is for debugging only

## Troubleshooting

### Server not appearing in Claude Desktop

1. Check config file path is correct
2. Verify JSON is valid (use a JSON validator)
3. Check absolute path to auphonic-mcp-server.clj
4. Restart Claude Desktop completely
5. Check Claude Desktop logs:
   - macOS: `~/Library/Logs/Claude/`
   - Linux: `~/.config/Claude/logs/`

### "AUPHONIC_API_KEY environment variable not set"

Environment variables are set in the Claude Desktop config, not in your shell:
```json
"env": {
  "AUPHONIC_API_KEY": "your-actual-key-here"
}
```

### "Upload failed: File not found"

- Use absolute paths, not relative: `/Users/you/file.mp3` not `~/file.mp3`
- Claude can't access files outside allowed directories
- Check file actually exists at that path

### "Invalid show" or "Invalid type"

Valid combinations:
- lup: bootleg, adfree, main
- launch: bootleg, main

Check your spelling and capitalization (use lowercase).

### Tools not working

1. Check environment variables are set in config
2. Verify API key is valid: https://auphonic.com/account
3. Test with HTTP mode to see detailed errors:
   ```bash
   ./auphonic-mcp-server.clj http 3000
   ```
4. Check Auphonic API status: https://status.auphonic.com/

## Workflow Examples

### Complete Upload → Process → Download

```
You: "I have a new LUP episode at /path/to/lup-0653.mp3. 
      Upload it as a bootleg, check the status periodically, 
      and download it when done to ~/Downloads/"

Claude will:
1. Use upload_audio tool
2. Track the production UUID
3. Use check_status to monitor
4. Use download_output when status is "Done"
```

### Batch Status Check

```
You: "Check the status of these productions: abc123, def456, ghi789"

Claude will:
1. Use check_status for each UUID
2. Summarize which are done, processing, or have errors
```

### Production Analysis

```
You: "Use the analyze_production prompt for abc123"

Claude will:
1. Fetch production details as a resource
2. Provide comprehensive analysis
3. Suggest next steps
```

## Advanced Usage

### Combining with Other Tools

If you have other MCP servers (e.g., filesystem, git), Claude can coordinate:

```
You: "Find the latest .mp3 file in ~/recordings/, 
      upload it to Auphonic as LUP bootleg, 
      and when done, commit it to git"
      
Claude can use:
- Filesystem MCP: find the file
- Auphonic MCP: upload and process
- Git MCP: commit when ready
```

### Custom Metadata

```
You: "Upload lup-0653.mp3 with title 'Episode 653: Great Show', 
      subtitle 'We discuss Linux', 
      and summary 'In this episode...'"
      
Claude will pass all metadata to upload_audio tool.
```

## Limitations

- Max file size depends on your Auphonic account plan
- Processing time varies (typically 1-5 minutes per hour of audio)
- Polling for status (no webhooks in MCP currently)
- STDIO mode is local-only (can't be accessed remotely)

## Future Enhancements

Potential additions:
- [ ] Multitrack support
- [ ] Chapter marks handling
- [ ] Webhook integration for status updates
- [ ] Batch upload support
- [ ] Direct S3/Dropbox integration
- [ ] Custom algorithm parameters
- [ ] Production templates

## Resources

- **MCP Specification**: https://modelcontextprotocol.io/specification/2025-06-18
- **Auphonic API Docs**: https://auphonic.com/help/api/
- **Babashka**: https://babashka.org/
- **Claude Desktop**: https://claude.ai/download

## Contributing

To modify or extend this server:

1. Follow MCP best practices (see MCP_BEST_PRACTICES.md)
2. Test with MCP Inspector before deploying
3. Document new tools/resources/prompts
4. Update this README

## License

MIT (or whatever you prefer)

## Support

For issues:
- Auphonic API: support@auphonic.com
- MCP Protocol: https://github.com/modelcontextprotocol/specification
- This server: [your contact/repo]
