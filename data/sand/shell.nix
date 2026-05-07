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
  nixpkgs =
    if data ? nixpkgs && data.nixpkgs ? locked then
      fetchTarball {
        url = "https://github.com/${data.nixpkgs.locked.owner}/${data.nixpkgs.locked.repo}/archive/${data.nixpkgs.locked.rev}.tar.gz";
        sha256 = data.nixpkgs.locked.narHash;
      }
    else
      <nixpkgs>;
  pkgs' = if pkgs != null then pkgs else import nixpkgs { };
in
pkgs'.mkShell {
  buildInputs = (if data ? shellPkgs then map (str: pkgs'.${str}) data.shellPkgs else [ ]);
}
