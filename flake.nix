{
  description = "Auphonic MCP Server - HTTP Model Context Protocol server for Auphonic API";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = nixpkgs.legacyPackages.${system};

        # Main server script
        auphonic-mcp-server = pkgs.writeShellApplication {
          name = "auphonic-mcp-server";
          runtimeInputs = [pkgs.babashka pkgs.curl];
          text = ''
            exec ${pkgs.babashka}/bin/bb ${./auphonic-mcp-server.clj} "$@"
          '';
        };

        # Test suite script
        auphonic-mcp-test = pkgs.writeShellApplication {
          name = "auphonic-mcp-test";
          runtimeInputs = [pkgs.babashka];
          text = ''
            exec ${pkgs.babashka}/bin/bb ${./test-runner.clj}
          '';
        };
      in {
        # Default package
        packages = {
          default = auphonic-mcp-server;
          server = auphonic-mcp-server;
          test = auphonic-mcp-test;
        };

        # Apps for nix run
        apps = {
          default = {
            type = "app";
            program = "${auphonic-mcp-server}/bin/auphonic-mcp-server";
          };
          server = {
            type = "app";
            program = "${auphonic-mcp-server}/bin/auphonic-mcp-server";
          };
          test = {
            type = "app";
            program = "${auphonic-mcp-test}/bin/auphonic-mcp-test";
          };
        };

        # Development shell
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.babashka
            pkgs.curl
          ];

          shellHook = ''
            echo "🎙️  Auphonic MCP Server Development Environment"
            echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            echo "Babashka version: $(bb --version)"
            echo ""
            echo "Available commands:"
            echo "  bb auphonic-mcp-server.clj 3000  - Run server on port 3000"
            echo "  bb test-runner.clj                 - Run test suite"
            echo "  bb --version                      - Check Babashka version"
            echo ""
            echo "Required environment variables:"
            echo "  AUPHONIC_API_KEY        - Your Auphonic API key"
            echo "  AUPHONIC_PRESET_LUP     - Preset UUID for LUP show"
            echo "  AUPHONIC_PRESET_LAUNCH  - Preset UUID for Launch show"
            echo ""

            # Check if required env vars are set
            if [ -z "$AUPHONIC_API_KEY" ]; then
              echo "⚠️  AUPHONIC_API_KEY not set"
            else
              echo "✓ AUPHONIC_API_KEY is set"
            fi

            if [ -z "$AUPHONIC_PRESET_LUP" ]; then
              echo "⚠️  AUPHONIC_PRESET_LUP not set"
            else
              echo "✓ AUPHONIC_PRESET_LUP is set"
            fi

            if [ -z "$AUPHONIC_PRESET_LAUNCH" ]; then
              echo "⚠️  AUPHONIC_PRESET_LAUNCH not set"
            else
              echo "✓ AUPHONIC_PRESET_LAUNCH is set"
            fi

            echo ""
          '';
        };

        # NixOS module for system-wide installation
        nixosModules.default = {
          config,
          lib,
          pkgs,
          ...
        }:
          with lib; let
            cfg = config.services.auphonic-mcp;
          in {
            options.services.auphonic-mcp = {
              enable = mkEnableOption "Auphonic MCP Server";

              port = mkOption {
                type = types.port;
                default = 3000;
                description = "Port to listen on";
              };

              openFirewall = mkOption {
                type = types.bool;
                default = false;
                description = "Open port in firewall";
              };

              environmentFile = mkOption {
                type = types.path;
                description = "File containing AUPHONIC_API_KEY and AUPHONIC_PRESET_* variables";
              };

              user = mkOption {
                type = types.str;
                default = "auphonic-mcp";
                description = "User to run the service as";
              };

              group = mkOption {
                type = types.str;
                default = "auphonic-mcp";
                description = "Group to run the service as";
              };
            };

            config = mkIf cfg.enable {
              systemd.services.auphonic-mcp = {
                description = "Auphonic MCP Server";
                wantedBy = ["multi-user.target"];
                after = ["network.target"];

                serviceConfig = {
                  Type = "simple";
                  User = cfg.user;
                  Group = cfg.group;
                  Restart = "on-failure";
                  RestartSec = "5s";

                  ExecStart = "${auphonic-mcp-server}/bin/auphonic-mcp-server ${toString cfg.port}";

                  # Security hardening
                  NoNewPrivileges = true;
                  PrivateTmp = true;
                  ProtectSystem = "strict";
                  ProtectHome = true;
                  ReadWritePaths = [];
                };

                environmentFile = cfg.environmentFile;
              };

              networking.firewall.allowedTCPPorts = mkIf cfg.openFirewall [cfg.port];

              # Create user and group
              users.users.${cfg.user} = {
                isSystemUser = true;
                group = cfg.group;
                description = "Auphonic MCP Server user";
              };

              users.groups.${cfg.group} = {};
            };
          };

        # Home Manager module for user installation
        homeManagerModules.default = {
          config,
          lib,
          pkgs,
          ...
        }:
          with lib; let
            cfg = config.programs.auphonic-mcp;
          in {
            options.programs.auphonic-mcp = {
              enable = mkEnableOption "Auphonic MCP Server";

              package = mkOption {
                type = types.package;
                default = auphonic-mcp-server;
                description = "The Auphonic MCP Server package to use";
              };

              apiKey = mkOption {
                type = types.nullOr types.str;
                default = null;
                description = "Auphonic API key (or use apiKeyFile)";
              };

              apiKeyFile = mkOption {
                type = types.nullOr types.path;
                default = null;
                description = "Path to file containing API key";
              };

              presets = mkOption {
                type = types.attrsOf types.str;
                default = {};
                example = {
                  lup = "preset-uuid-1";
                  launch = "preset-uuid-2";
                };
                description = "Show name to preset UUID mapping";
              };
            };

            config = mkIf cfg.enable {
              home.packages = [cfg.package];

              home.sessionVariables =
                (optionalAttrs (cfg.apiKey != null) {
                  AUPHONIC_API_KEY = cfg.apiKey;
                })
                // (mapAttrs' (
                    name: value:
                      nameValuePair "AUPHONIC_PRESET_${toUpper name}" value
                  )
                  cfg.presets);
            };
          };
      }
    );
}
