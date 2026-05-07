alias b := build
alias fmt := format
alias t := test
alias u := update

[private]
list:
    @# First command in the file is invoked by default
    @just --list

# Run benchmarks
bench *args: build
    finefile bench {{ args }}

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
    PATH="$(nix build . --print-out-paths)/bin:$PATH" clojure -M:test -m sand.test-runner {{ args }} --show-stderr

# Run tests inline (no build required)
test-inline *args:
    #!/usr/bin/env bash
    SAND_DATA_DIR="$(realpath ./data/sand)" SAND_SCHEMA="$(realpath ./schema/sand.toml.latest.schema.json)" clojure -M:test -m sand.test-runner --inline {{ args }} --show-stderr

# Run tests against the jar app, for development
test-jar *args:
    #!/usr/bin/env bash
    PATH="$(just _jar-app-path)/bin:$PATH" SAND_DATA_DIR="$(realpath ./data/sand)" SAND_SCHEMA="$(realpath ./schema/sand.toml.latest.schema.json)" clojure -M:test -m sand.test-runner {{ args }} --show-stderr

# Update dependencies
update: && update-deps-lock format
    nix flake update
    clj -M:antq --upgrade --force

# Update deps-lock.json after changing Clojure deps
update-deps-lock:
    deps-lock deps.edn
