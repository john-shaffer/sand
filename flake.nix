{
  description = "sand development environments";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-25.11";
    clj-nix = {
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:jlesquembre/clj-nix";
    };
    finefile = {
      inputs.clj-nix.follows = "clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
      url = "github:john-shaffer/finefile";
    };
    flake-utils.url = "github:numtide/flake-utils";
  };
  outputs =
    inputs:
    with inputs;
    flake-utils.lib.eachDefaultSystem (
      system:
      with import nixpkgs {
        inherit system;
        overlays = [ clj-nix.overlays.default ];
      };
      let
        jdkPackage = pkgs.jdk25_headless;
        lockfile = lib.sources.sourceByRegex self [ "^deps-lock.json$" ];
        sandSrc = lib.sources.sourceFilesBySuffices self [
          ".clj"
          ".edn"
        ];
        sandData = lib.sources.sourceFilesBySuffices self [
          ".json"
          ".nix"
          ".toml"
        ];
        sandBin = clj-nix.lib.mkCljApp {
          pkgs = nixpkgs.legacyPackages.${system};
          modules = [
            {
              jdk = jdkPackage;
              lockfile = lockfile + /deps-lock.json;
              main-ns = "sand.cli";
              name = "sand";
              nativeImage.enable = true;
              projectSrc = sandSrc;
              version = "0.1.0";
            }
          ];
        };
        sandUnwrapped = stdenv.mkDerivation {
          inherit (sandBin) meta name version;
          phases = [ "installPhase" ];
          installPhase = ''
            mkdir -p $out/bin $out/share/sand
            cp ${sandBin}/bin/sand $out/bin/sand
            cp -r ${sandData}/data/sand $out/share
            cp ${sandData}/schema/sand.toml.latest.schema.json $out/share/sand
          '';
        };
        runtimePaths = [
          pkgs.git
          pkgs.taplo
        ];
        sandWrapped =
          runCommand sandUnwrapped.name
            {
              inherit (sandUnwrapped) meta name version;

              nativeBuildInputs = [ makeWrapper ];
            }
            ''
              mkdir -p $out/bin
              makeWrapper ${sandUnwrapped}/bin/sand $out/bin/sand \
                --prefix PATH : ${lib.makeBinPath runtimePaths} \
                --set-default SAND_DATA_DIR ${sandUnwrapped}/share/sand \
                --set-default SAND_SCHEMA ${sandUnwrapped}/share/sand/sand.toml.latest.schema.json
            '';
        sandShell = import ./.sand/shell.nix { inherit pkgs; };
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs =
            with pkgs;
            [
              clojure
              deps-lock
              finefile.packages.${system}.default
              just
            ]
            ++ runtimePaths
            ++ sandShell.buildInputs;
          shellHook = ''
            echo
            echo -e "Run '\033[1mjust <recipe>\033[0m' to get started"
            just --list
          '';
          SAND_DATA_DIR = "data/sand";
        };
        packages = {
          default = sandWrapped;
          sand = sandWrapped;
          sand-unwrapped = sandUnwrapped;
        };
      }
    );
}
