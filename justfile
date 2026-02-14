repo_root := `pwd`

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

# Format source and then check for unfixable issues
format:
    just run format
    standard-clj fix

# Run sand
run *args:
    clojure -M -m sand.cli {{ args }}

# Run tests
test *args:
    SAND_DATA_DIR={{ repo_root }}/data/sand SAND_SCHEMA={{ repo_root }}/schema/sand.toml.latest.schema.json clojure -M:test -m sand.test-runner result/bin/sand {{ args }}

# Update dependencies
update: && update-deps-lock
    nix flake update
    clj -M:antq --upgrade --force

# Update deps-lock.json after changing Clojure deps
update-deps-lock:
    deps-lock deps.edn
