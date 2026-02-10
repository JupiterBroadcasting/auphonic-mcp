{
  description = "Auphonic MCP Server";

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
        auphonic-mcp-server = pkgs.writeShellApplication {
          name = "auphonic-mcp-server";
          runtimeInputs = [pkgs.babashka];
          text = ''
            bb ${./auphonic-mcp-server.clj} "$@"
          '';
        };
      in {
        packages.default = auphonic-mcp-server;
        devShells.default = pkgs.mkShell {
          buildInputs = [pkgs.babashka];
        };
      }
    )
    // {
      nixosModules.default = {
        config,
        lib,
        pkgs,
        ...
      }: let
        cfg = config.services.auphonic-mcp;
        inherit (lib) mkEnableOption mkOption types mkIf;
      in {
        options.services.auphonic-mcp = {
          enable = mkEnableOption "Auphonic MCP Server";
          port = mkOption {
            type = types.port;
            default = 3000;
            description = "Port to listen on (HTTP mode)";
          };
          openFirewall = mkOption {
            type = types.bool;
            default = false;
            description = "Open port in firewall";
          };
          environmentFile = mkOption {
            type = types.path;
            description = "File containing AUPHONIC_API_KEY and other secrets";
          };
          user = mkOption {
            type = types.str;
            default = "nobody";
            description = "User to run the service as";
          };
          group = mkOption {
            type = types.str;
            default = "nogroup";
            description = "Group to run the service as";
          };
        };

        config = mkIf cfg.enable {
          systemd.services.auphonic-mcp = {
            description = "Auphonic MCP Server";
            after = ["network.target"];
            wantedBy = ["multi-user.target"];
            serviceConfig = {
              ExecStart = "${self.packages.${pkgs.system}.default}/bin/auphonic-mcp-server http ${toString cfg.port}";
              EnvironmentFile = cfg.environmentFile;
              Restart = "always";
              User = cfg.user;
              Group = cfg.group;
              # Sandboxing
              ProtectSystem = "full";
              ProtectHome = "read-only"; # Changed from true to read-only to allow reading home dir if needed
              NoNewPrivileges = true;
              PrivateTmp = true;
            };
          };
          networking.firewall.allowedTCPPorts = mkIf cfg.openFirewall [cfg.port];
        };
      };
    };
}
