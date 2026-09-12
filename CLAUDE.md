# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Paper plugin that trades a physical item for GriefPrevention claim blocks, with
no Vault and no balances. Single Maven module, six classes, ~18 KB jar. The
scope is deliberately narrow — see `ROADMAP.md` "Not planned" before proposing
features.

## Build and test

```bash
./mvnw clean package     # jar lands in target/ClaimBarter-<version>.jar
./mvnw -B verify         # what CI runs
```

**JDK 25 or newer is required to build**, even though `maven.compiler.release`
is 21. `paper-api` for Paper 26.x ships Java 25 class files that a JDK 21
`javac` cannot read; the output still targets Java 21 so one jar runs on both
Paper 1.21.x (Java 21) and Paper 26.x (Java 25). Do not "fix" this mismatch by
raising `release`.

**There is no automated test suite and no `src/test`.** CI proves only that the
project compiles and packages. Verification means running the jar on a real
Paper server next to GriefPrevention and exercising `buy`, `sell`, `info`, and
`reload`, then restarting to confirm claim block totals survived. Never report a
behavioural change as verified on the strength of a successful build.

## Architecture

`ClaimBarterPlugin` (the only public class) owns three fields that `load()`
rebuilds wholesale on every reload:

- `BarterSettings` — an immutable record validated at construction from
  `config.yml`. Every bad value throws `InvalidSettingException` here rather
  than at the point of use, and the plugin disables itself rather than running
  on a config it cannot honour.
- `Messages` — the `messages:` block, rendered through Adventure's legacy
  ampersand serializer.
- `BarterService` — all trade logic; holds the settings snapshot, so it must be
  recreated whenever settings are.

`BarterCommand` is the only caller of the service and resolves every outcome
through `BarterService.Result` (a message key plus alternating key/value
placeholders). It caches `plugin.messages()` at the top of `onCommand`, so after
`plugin.load()` it must re-read — the old instance is stale.

### Invariants that are easy to break silently

- **`PlayerData.setBonusClaimBlocks()` does not persist.**
  `GriefPrevention.instance.dataStore.savePlayerData()` must follow it or the
  change is lost on reload. This is the single easiest way to write a plugin
  that appears to work while quietly eating players' items.
- **Transaction ordering is a safety property, not style.** Buying takes items
  first and grants blocks second; selling removes and persists blocks first and
  hands over items second. A failure mid-transaction must cost the plugin, never
  the server. `SECURITY.md` treats a change that defeats this as a
  vulnerability, not a bug.
- **Only the bonus pool is ever written or sold.** Blocks a player accrued by
  playing are never sellable — that would turn idle time into an item faucet.
- **Only plain stacks in the main inventory count as currency.** Anything with
  item meta is skipped, and armor, offhand, and ender chests are not searched.
  Both are intentional (`README.md`, "What it deliberately does not do").
- **The command argument is always what the player hands over** — `buy` counts
  items, `sell` counts blocks. This avoids the rounding trap where asking for
  150 blocks at 100-per-item silently charges two items.

### Config and resources

`src/main/resources` is Maven-filtered, so `plugin.yml` draws name, version, and
description from `pom.xml`. Any literal `${...}` added to a resource will be
substituted.

`saveDefaultConfig()` never overwrites an existing server `config.yml`. Adding a
new key therefore has to survive its absence:

- Settings keys pass a default to `config.getInt`/`getString`/etc. in
  `BarterSettings.from`.
- Message keys have **no** defaults — a key missing from an upgraded server's
  config renders `&cMissing message: <key>` to the player. Adding one is a
  user-visible change worth calling out in the commit.

All player-facing text lives in `config.yml` under `messages:`. Never hardcode a
player-facing string in Java.

## Code style

Allman braces, package-private classes except the plugin entry point, and
Javadoc that explains *why* a decision was made rather than restating the
signature. Match it — it mirrors GriefPrevention's own style.

## Branching and releases

```
feature/*, fix/*  --(squash)-->  dev  --(ff-only)-->  staging  --(ff-only)-->  main
```

- **Pull requests target `dev`.** Never commit directly to `staging` or `main`.
- **Promotions are `--ff-only`.** Squashing would collapse the conventional
  commits `release-please` reads; a merge commit makes it duplicate changelog
  entries. If a fast-forward is refused, rebase — do not merge.
- PRs are squash-merged, so **the PR title is the commit message** and CI lints
  it against Conventional Commits. Types: `feat`, `fix`, `docs`, `refactor`,
  `perf`, `test`, `build`, `ci`, `chore`. Optional scopes: `barter`, `config`,
  `messages`, `command`, `gp`, `build`. Subject starts lowercase, no trailing
  period.
- **Never bump `<version>` in `pom.xml` and never edit `CHANGELOG.md`.** Both
  are generated by `release-please` from commits on `main`. Pushing to `staging`
  publishes an `-rc.N` prerelease; the prerelease workflow asserts it committed
  nothing, so anything that writes to the repo there will fail the build.
