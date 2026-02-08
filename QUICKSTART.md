# Auphonic MCP Server - Quick Start Guide

Get up and running in 5 minutes!

## Prerequisites

```bash
# Install Babashka (if not already installed)
# macOS:
brew install borkdude/brew/babashka

# Linux:
curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
chmod +x install && ./install
```

## Setup Steps

### 1. Get Your Auphonic API Key

1. Go to https://auphonic.com/account
2. Copy your API key

### 2. Get Your Preset UUIDs

1. Go to https://auphonic.com/presets
2. Click on a preset you want to use
3. Copy the UUID from the URL (e.g., `https://auphonic.com/preset/abc123xyz`)
4. Do this for both LUP and Launch presets (or create new ones)

### 3. Configure Claude Desktop

**macOS:** Edit `~/Library/Application Support/Claude/claude_desktop_config.json`  
**Linux:** Edit `~/.config/Claude/claude_desktop_config.json`

Add this:

```json
{
  "mcpServers": {
    "auphonic": {
      "command": "bb",
      "args": [
        "/Users/yourname/path/to/auphonic-mcp-server.clj",
        "stdio"
      ],
      "env": {
        "AUPHONIC_API_KEY": "paste-your-api-key-here",
        "AUPHONIC_PRESET_LUP": "paste-lup-preset-uuid-here",
        "AUPHONIC_PRESET_LAUNCH": "paste-launch-preset-uuid-here"
      }
    }
  }
}
```

**Important:** 
- Replace `/Users/yourname/path/to/` with the actual absolute path
- Replace the placeholder values with your actual keys/UUIDs

### 4. Make Script Executable

```bash
chmod +x auphonic-mcp-server.clj
```

### 5. Restart Claude Desktop

Completely quit and restart Claude Desktop.

### 6. Verify It's Working

In Claude Desktop, look for the 🔌 icon. You should see:
- 6 tools available
- 3 prompts available
- Auphonic server listed

## First Test

Try asking Claude:

```
"Can you list my Auphonic presets?"
```

Claude should use the `list_presets` tool and show you your presets.

## Common First Commands

```
"Upload /path/to/audio.mp3 as a bootleg episode for LUP"

"Check the status of production abc123xyz"

"List my recent Auphonic productions"

"Download production abc123xyz to ~/Downloads"
```

## Troubleshooting

### Server not showing up in Claude Desktop?

1. **Check the config file path**
   - macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
   - Linux: `~/.config/Claude/claude_desktop_config.json`

2. **Validate JSON**
   - Use https://jsonlint.com/ to check for syntax errors
   - Make sure all quotes are straight quotes, not curly quotes

3. **Check absolute path**
   - Must be full path like `/Users/you/scripts/auphonic-mcp-server.clj`
   - NOT `~/scripts/auphonic-mcp-server.clj`

4. **Check logs**
   - macOS: `~/Library/Logs/Claude/`
   - Linux: `~/.config/Claude/logs/`

### Test manually

```bash
# Set environment variables
export AUPHONIC_API_KEY="your-key"
export AUPHONIC_PRESET_LUP="your-preset"

# Run test
./test-auphonic-mcp.clj
```

### Debug mode

```bash
# Run in HTTP mode to see detailed errors
export AUPHONIC_API_KEY="your-key"
export AUPHONIC_PRESET_LUP="your-preset"
./auphonic-mcp-server.clj http 3000

# Test with curl
curl -X POST http://localhost:3000/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

## What You Can Do Now

### Upload and Process Audio
```
"I have a new LUP episode at /path/to/lup-653.mp3. Upload it as bootleg."
```

### Monitor Status
```
"Check if production abc123 is done processing"
```

### Download Results
```
"Download production abc123 to ~/Downloads"
```

### Analyze Productions
```
"Analyze production abc123 and tell me if it's ready to publish"
```

### Batch Operations
```
"Check the status of all my recent uploads"
```

## Next Steps

- Read the full README: `AUPHONIC_MCP_README.md`
- Learn MCP best practices: `MCP_BEST_PRACTICES.md`
- Explore Auphonic API: `AUPHONIC_API.md`

## Need Help?

Check the troubleshooting section in `AUPHONIC_MCP_README.md` for detailed solutions.
