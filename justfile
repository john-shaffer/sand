alias b := build
alias fmt := format
alias t := test
alias u := update

[private]
list:
    @# First command in the file is invoked by default
    @just --list

# Run benchmarks
bench *args: build _bench-fixtures
    finefile bench {{ args }}

# Create a git repo for the format benchmarks to run in
_bench-fixtures:
    #!/usr/bin/env bash
    set -euo pipefail
    dir=.bench/format
    rm -rf "$dir"
    mkdir -p "$dir/repo" "$dir/bin"
    # shfmt stands in for any formatter. It isn't in sand's formatters.toml
    # yet, so add it to a copy.
    cp -r data/sand "$dir/data"
    chmod -R u+w "$dir/data"
    printf '\n[shfmt]\nargs = ["-w", "*@"]\nextensions = ["sh"]\npackage = "shfmt"\n' >> "$dir/data/formatters.toml"
    cd "$dir/repo"
    git init -q .
    printf 'if true;then\n    echo  hi\nfi\n' > ../example.sh.orig
    cp ../example.sh.orig example.sh
    git add example.sh
    nix-shell -p shfmt --run 'ln -s "$(command -v shfmt)" ../bin/shfmt'

# Build the sand package
build:
    nix build

# Format source
format:
    just run format
    standard-clj fix

# Jar runner that is quick to build for development
_jar-app-path:
    @nix build .#sand-jar-app --print-out-paths

# Data dir as installed, with the nixpkgs that sand was built with
_data-dir:
    @echo "$(nix build .#sand-data --print-out-paths)/share/sand"

# Run sand
run *args:
    #!/usr/bin/env bash
    SAND_DATA_DIR="$(realpath ./data/sand)" SAND_SCHEMA="$(realpath ./schema/sand.toml.latest.schema.json)" clojure -M -m sand.cli {{ args }}

# Run tests against the built binary (all scenarios, or the given ones)
test *args:
    #!/usr/bin/env bash
    set -euo pipefail
    # Keep tests independent of the user's cache
    export SAND_CACHE_DIR="$(mktemp -d)"
    trap 'rm -rf "$SAND_CACHE_DIR"' EXIT
    # For scenarios that need a modified copy of sand's data files
    export SAND_SOURCE_DATA_DIR="$(realpath ./data/sand)"
    PATH="$(nix build . --print-out-paths)/bin:$PATH" tact {{ if args == "" { "test" } else { args } }}

# Run tests against the jar app, for development (all scenarios, or the given ones)
test-jar *args:
    #!/usr/bin/env bash
    set -euo pipefail
    # Keep tests independent of the user's cache
    export SAND_CACHE_DIR="$(mktemp -d)"
    trap 'rm -rf "$SAND_CACHE_DIR"' EXIT
    # For scenarios that need a modified copy of sand's data files
    export SAND_SOURCE_DATA_DIR="$(realpath ./data/sand)"
    data_dir="$(just _data-dir)"
    PATH="$(just _jar-app-path)/bin:$PATH" SAND_DATA_DIR="$data_dir" SAND_SCHEMA="$data_dir/sand.toml.latest.schema.json" tact {{ if args == "" { "test" } else { args } }}

# Update dependencies
update: && update-deps-lock format
    nix flake update
    clj -M:antq --upgrade --force

# Update deps-lock.json after changing Clojure deps
update-deps-lock:
    deps-lock deps.edn
