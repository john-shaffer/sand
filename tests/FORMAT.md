# Test Format

Each `.md` file in this directory is a test case.

## Structure

```markdown
# Test name

Optional description.

## Files

### path/to/file.ext
```lang
content
```

## Run

```shell
command to execute
```

## Expected path/to/file.ext
```lang
expected content after run
```

## Expected stdout
```
expected stdout (optional)
```

## Expected stderr
```
expected stderr (optional)
```

## Expected exit
```
0
```
```

## Sections

- **Files** - Create these files in a temp directory before running
- **Run** - Shell command to execute (working dir is the temp directory)
- **Expected \<path\>** - Assert file contents after run
- **Expected stdout** - Assert stdout (optional, default: don't check)
- **Expected stderr** - Assert stderr (optional, default: don't check)
- **Expected exit** - Assert exit code (optional, default: 0)
