{
  description = "sand development environment";

  inputs = {
    sand.url = "./..";
    nixpkgs.follows = "sand/nixpkgs";
    clj-nix.follows = "sand/clj-nix";
    finefile = {
      inputs.clj-nix.follows = "clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:john-shaffer/finefile";
    };
    tact = {
      inputs.clj-nix.follows = "clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:john-shaffer/tact";
    };
  };
  outputs =
    inputs: with inputs; {
      devShells = nixpkgs.lib.genAttrs (builtins.attrNames sand.packages) (
        system:
        let
          pkgs = nixpkgs.legacyPackages.${system};
        in
        {
          default = pkgs.mkShell {
            buildInputs = with pkgs; [
              clj-nix.packages.${system}.deps-lock
              clojure
              finefile.packages.${system}.default
              git
              just
              taplo
              tact.packages.${system}.default
            ];
            shellHook = ''
              echo
              echo -e "Run '\033[1mjust <recipe>\033[0m' to get started"
              just --list
            '';
          };
        }
      );
    };
}
