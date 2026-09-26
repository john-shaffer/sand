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
  pkgsDescription =
    if pkgs != null then
      "the given pkgs"
    else if data ? nixpkgs && data.nixpkgs ? locked then
      "nixpkgs ${data.nixpkgs.locked.rev}"
    else
      "<nixpkgs>";
  # A package can disappear when nixpkgs is updated, so give a clearer
  # error than nix's missing attribute error.
  getPkg =
    name:
    if pkgs' ? ${name} then
      pkgs'.${name}
    else
      throw "sand: package '${name}' isn't in ${pkgsDescription}";
in
pkgs'.mkShell {
  buildInputs = (if data ? shellPkgs then map getPkg data.shellPkgs else [ ]);
}
