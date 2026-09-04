# wet-glue

[![CI](https://github.com/Noob-stupid/wet-glue/actions/workflows/ci.yml/badge.svg)](https://github.com/Noob-stupid/wet-glue/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[中文文档](README.zh-CN.md)

> **Two comment lines to manage the code you're not sure about.**
> Mark it → swap implementations → benchmark → pick the winner → weld it shut. Swapping is source-level text replacement with zero runtime overhead; once sealed, it's just plain code—as if it was always meant to be that way.

```java
public String get(int key) {
    // glue:begin lookup            ← head
    return rbtree.get(key);          ← the part you're not sure about
    // glue:end                      ← tail
}
```

```bash
glue scan                    # discover all glue regions
glue add  lookup bplus       # register current impl as a candidate
glue use  lookup bplus       # swap (source text replacement, no indirection)
glue verify lookup           # compile + benchmark both versions, one command
glue seal lookup             # lock in the winner (auto-verifies, then confirms)
```

## Why not just "comment out the old code"

That's what most of us do today: write the new impl next to the old one, comment the old one out, delete it later if things work. wet-glue beats that habit:

| | comment-out | wet-glue |
|---|---|---|
| How many candidates exist, which is active | squint at comments | `glue list` |
| Swap | manual, error-prone | `glue use` |
| Do both versions behave the same | pray | `glue verify` proves it |
| Finalize | delete comments, no record | `glue seal` + audit trail |
| Someone broke it by hand | nobody notices | `glue check` reports drift, CI-blockable |

It's also not a feature-flag platform (those toggle business behavior at runtime) or a DI container (that requires extracting interfaces and rewriting call sites). wet-glue works at **edit time**: decisions happen while writing code, not while running it.

## Why it matters in the AI era

Your AI assistant hands you three implementations a day. B+ tree or red-black tree? Don't trust the LLM's vibes—trust the benchmark. Register every candidate, run the numbers, pick with data, then weld it. **Decisions become records, not hunches.**

## Real-world case study

**[Validating wet-glue on NanoHTTPD](docs/case-nanohttpd.md)** — first full run on someone else's real code (5k+ stars): registered a thread-pool candidate against the classic thread-per-connection model, benchmarked both, sealed based on data. Counter-intuitive result: the original won at both load levels. That's exactly why glue exists.

## Quick start

Requires JDK 17+ (the tool compiles itself on first run).

```bash
git clone https://github.com/Noob-stupid/wet-glue.git
cd wet-glue
./glue list            # ships with Java + C# example regions
./glue use lookup bplus
./glue verify lookup   # benchmark both (C# example needs dotnet)
```

Windows: `glue.cmd`. Git Bash / Linux / macOS: `./glue`.

Or grab `glue.jar` from [Releases](https://github.com/Noob-stupid/wet-glue/releases) and run `java -jar glue.jar` anywhere.

## Commands

| Command | What it does |
|---|---|
| `glue scan` | scan sources, register all glue regions |
| `glue list` | regions, status (experimental / candidate / sealed), candidates, "is the glue drying" |
| `glue add <region> <alias>` | register region's current content as a candidate |
| `glue use <region> <alias>` | swap candidate (source replacement) — diffs external contracts before swapping |
| `glue refs <region>` | the region's implicit contract with the outside (what it reads/writes) |
| `glue seal <region>` | **seal**: auto-runs verification first (failure blocks sealing), then double-confirms, then welds |
| `glue unseal <region>` | re-insert markers, make swappable again |
| `glue verify <region> [cmd]` | register/run verification command (exit codes propagate, CI-ready) |
| `glue check` | drift & glue-leak detection (exit 1 on problems, drop into CI) |

## Handling mechanical differences (different signatures, different deps)

If the incoming implementation **depends on different externals** (e.g. `rbtree.get()` → binary search over `bpKeys`), the tool shows the diff *before* swapping:

```
⚠ Candidate implementations have different external contracts (rbtree → bplus):
  + now reads: bpKeys, bpVals
  - no longer reads: rbtree
```

- **Symbol-level diffs** (renames, dep changes): visible before you swap, one glance to confirm.
- **Type-level diffs** (real signature/return-type changes): `glue verify` lets the compiler pinpoint the breakage; you write the adaptation into the candidate fragment (adaptation is just plain code in the fragment).
- **Verification gate**: for regions with a registered verify command, `seal` runs it first and **refuses to seal on failure** ("sealed = tests pass + human confirm").

## Supported languages

Any language whose line comment is `//` works out of the box—markers and engine logic are identical, files are matched by extension, and adding a language is one line:

`.java` `.cs` `.kt` `.go` `.ts` `.js` `.c` `.cpp` `.rs` `.swift` … (see `SRC_EXT` in `src/Glue.java`)

`examples/csharp/` has a full C# demo: same tool, same markers, whole lifecycle works on C# code.
(`#`-comment languages like Python need a different marker convention—planned.)

## Design principles

- **Zero runtime overhead**: swapping happens at edit time; the artifact has no indirection. Sealed code is plain code, strictly equivalent to handwritten.
- **Automatic status flow**: experimental → (≥2 candidates) → candidate → sealed (↔ unseal).
- **Seal protection**: double confirmation; refuses to seal in CI by default (prevents unattended sealing). Explicit escape hatch for test automation: `GLUE_ALLOW_SEAL=1`.
- **Full audit trail**: every change appended to `swappable.lock` (with git HEAD), meant to be committed.
- **Glue dries**: `list` shows days since last swap, nudging you to decide.
- **Leak detection**: if a name declared inside a region is referenced outside, the region can't be swapped wholesale—`check` reports it.

## Honest limitations

- **Same-language swapping only**. Different languages/runtimes on the two sides (Python algo → Rust impl) are out of scope—that's IDL + service territory.
- **Contract analysis is heuristic**: text-based, no type info. Same-named variables across scopes (e.g. `int i` inside the region vs. a loop `i` outside) may cause false leak positives—give region-local variables distinctive names. Type-level correctness is always the compiler's job (`verify`).
- **No compiler protection for the final choice**: if two candidates touch different externals, the tool warns but can't guarantee correctness. That's the price of "unsealed", and why you run the tests.

## Tests

```bash
bash tests/run.sh    # 39 end-to-end assertions, isolated temp dir, zero pollution
```

CI: GitHub Actions on every push (including .NET for the C# example).

## Roadmap

- `#`-comment languages (Python / Shell): marker convention branch.
- Build integration: wire swaps into Maven/Gradle/dotnet builds with auto-recompile.
- Risk grading: score regions by reference breadth + serialization coupling; high-risk swaps require `verify` first.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues and PRs welcome.

## License

[MIT](LICENSE)
