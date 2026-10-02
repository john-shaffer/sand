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
  # A package can disappear when nixpkgs is updated. Renamed packages
  # become aliases that throw, which tryEval catches.
  hasPkg = name: pkgs' ? ${name} && (builtins.tryEval pkgs'.${name}).success;
  aliasesPath = nixpkgsSource.path + "/pkgs/top-level/aliases.nix";
  aliasLines = builtins.filter builtins.isString (
    builtins.split "\n" (builtins.readFile aliasesPath)
  );
  # The new name of a package that nixpkgs renamed, or null. The alias
  # throws "'old' has been renamed to/replaced by 'new'", but tryEval
  # can't see the message, so it's read from the alias's source.
  renamedTo =
    name:
    let
      quoted = pkgs'.lib.escapeRegex name;
      matches = builtins.filter (m: m != null) (
        map (builtins.match "  ${quoted} = throw \"'${quoted}' has been renamed to/replaced by '([^']+)'\".*") aliasLines
      );
    in
    if pkgs != null || !builtins.pathExists aliasesPath || matches == [ ] then
      null
    else
      builtins.head (builtins.head matches);
  # The name that a package is found under, or null if it's missing
  resolvePkg =
    name:
    let
      newName = renamedTo name;
    in
    if hasPkg name then
      name
    else if newName != null && hasPkg newName then
      newName
    else
      null;
  shellPkgs = builtins.partition (name: resolvePkg name != null) (data.shellPkgs or [ ]);
in
pkgs'.mkShell (
  {
    buildInputs = map (name: pkgs'.${resolvePkg name}) shellPkgs.right;
  }
  # sand reads this to warn about the missing packages and skip what
  # needs them.
  // (
    if shellPkgs.wrong == [ ] then
      { }
    else
      {
        SAND_MISSING_PKGS = builtins.toJSON {
          nixpkgs = pkgsDescription;
          packages = shellPkgs.wrong;
        };
      }
  )
)
