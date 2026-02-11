# NEXT.md - Auphonic MCP Server

## Status: Merged to upstream main

All feat/flake changes have been merged to origin/main.

### What Changed

- flake.nix now includes full packages/apps/devShells structure
- NixOS module uses `environmentFile` for secrets (backward compatible with upstream configs)
- Home Manager module added for user-level installation
- Improved devShell with helpful echo output

______________________________________________________________________

## Test Suite

```bash
bb test-runner.clj
```

______________________________________________________________________

## NixOS Configuration Example

```nix
{ config, pkgs, ... }:

let
  # Create a secrets file outside of Nix store
  secretsFile = "/etc/nixos/secrets/auphonic-mcp.env";
in {
  imports = [ /* path to flake inputs.auphonic-mcp.nixosModules.default */ ];

  services.auphonic-mcp = {
    enable = true;
    port = 3000;
    openFirewall = true;
    environmentFile = secretsFile;
  };
}
```

Secrets file (`auphonic-mcp.env`):

```
AUPHONIC_API_KEY=your-api-key
AUPHONIC_PRESET_LUP=preset-uuid
AUPHONIC_PRESET_LAUNCH=preset-uuid
```

______________________________________________________________________

## Home Manager Configuration Example

```nix
{ config, pkgs, ... }:

{
  imports = [ /* path to flake inputs.auphonic-mcp.homeManagerModules.default */ ];

  programs.auphonic-mcp = {
    enable = true;
    apiKeyFile = "/home/you/.config/auphonic-mcp/key";
    presets = {
      lup = "preset-uuid";
      launch = "preset-uuid";
    };
  };
}
```

______________________________________________________________________

## Notes

- Tests run on port 3002 to avoid conflicts with other MCP servers
- API key required for tests that make actual Auphonic API calls
- Server-side validation handles show-specific episode types
