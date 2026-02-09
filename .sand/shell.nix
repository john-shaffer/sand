# AUTO-GENERATED: Do not modify.
{
  pkgs ? null,
}:

let
  jsonPath = builtins.path {
    path = ./sand.json;
    name = "sand-json";
  };
  data = builtins.fromJSON (builtins.readFile jsonPath);
  locked = data.nixpkgs.locked;
  nixpkgs = fetchTarball {
    url = "https://github.com/${locked.owner}/${locked.repo}/archive/${locked.rev}.tar.gz";
    sha256 = locked.narHash;
  };
  pkgs' = if pkgs != null then pkgs else import nixpkgs { };
in
pkgs'.mkShell {
  buildInputs = (if data ? shellPkgs then map (str: pkgs'.${str}) data.shellPkgs else [ ]);
}
