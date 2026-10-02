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
  # The nixpkgs that sand was built with. The build replaces null with its
  # locked github input.
  sandNixpkgs = null;
  fetchLocked =
    locked:
    fetchTarball {
      url = "https://github.com/${locked.owner}/${locked.repo}/archive/${locked.rev}.tar.gz";
      sha256 = locked.narHash;
    };
  nixPathNixpkgs = builtins.tryEval <nixpkgs>;
  # Prefer the flake's nixpkgs, then <nixpkgs>, then sand's own.
  nixpkgsSource =
    if data ? nixpkgs && data.nixpkgs ? locked then
      {
        path = fetchLocked data.nixpkgs.locked;
        description = "nixpkgs ${data.nixpkgs.locked.rev}";
      }
    else if nixPathNixpkgs.success then
      {
        path = nixPathNixpkgs.value;
        description = "<nixpkgs>";
      }
    else if sandNixpkgs != null then
      {
        path = fetchLocked sandNixpkgs;
        description = "nixpkgs ${sandNixpkgs.rev}";
      }
    else
      throw "sand: no nixpkgs found. Add a nixpkgs input to the flake.lock in this repo, or add nixpkgs to NIX_PATH.";
  pkgs' = if pkgs != null then pkgs else import nixpkgsSource.path { };
  pkgsDescription = if pkgs != null then "the given pkgs" else nixpkgsSource.description;
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
