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

# Run sand
run *args:
    #!/usr/bin/env bash
    SAND_DATA_DIR="$(realpath ./data/sand)" SAND_SCHEMA="$(realpath ./schema/sand.toml.latest.schema.json)" clojure -M -m sand.cli {{ args }}

# Run tests against the built binary
test *args:
    #!/usr/bin/env bash
    PATH="$(nix build . --print-out-paths)/bin:$PATH" tact test {{ args }}

# Run tests against the jar app, for development
test-jar *args:
    #!/usr/bin/env bash
    PATH="$(just _jar-app-path)/bin:$PATH" SAND_DATA_DIR="$(realpath ./data/sand)" SAND_SCHEMA="$(realpath ./schema/sand.toml.latest.schema.json)" tact test {{ args }}

# Update dependencies
update: && update-deps-lock format
    nix flake update
    clj -M:antq --upgrade --force

# Update deps-lock.json after changing Clojure deps
update-deps-lock:
    deps-lock deps.edn
