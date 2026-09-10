# Repository Maturity — Design

- **Date:** 2026-09-10
- **Status:** Approved (pending spec review)
- **Scope:** Repository infrastructure only. No changes to `src/`.

## 1. Context

ClaimBarter is a Paper plugin that trades a configured item for GriefPrevention
claim blocks without Vault. It works, it is published under GPL-3.0, and the
repository is deliberately minimal: a single commit (`f8233d1`), one branch
(`main`), no CI, no templates, no contribution guide, and a version number
hardcoded at `pom.xml:11`.

The goal is to make the repository read as trustworthy to a server administrator
evaluating whether to install the plugin, and to make releases come out without
manual steps.

### Stated objective

**"Serious enough to use."** Presentation and release automation come first;
test gates are explicitly out of scope. The maintainer was told that automating
releases over unverified code publishes bugs faster, and chose minimal gates
anyway. That decision is recorded and respected.

The single gate that remains is "it compiles and packages", because a red CI
badge or a jar that does not build destroys administrator trust faster than the
absence of tests does.

## 2. Goals

1. Automatic versioning driven by commit messages.
2. Long-lived branches separating work in progress, release candidates, and
   released code.
3. Enforced commit message conventions.
4. Issue and pull request templates.
5. A contribution guide.
6. A security policy.
7. A published compatibility matrix stating which Minecraft server versions and
   which GriefPrevention versions are supported — and which are not.
8. A roadmap grounded in real, already-documented limitations.

## 3. Non-goals

- **No tests.** Explicitly excluded by the maintainer. The build gate does not
  run tests because there are none.
- **No changes to `src/`.** This design touches repository infrastructure and
  documentation only.
- **No Gradle migration.** Verified unnecessary; see Decision D4.
- **No Modrinth or CurseForge publishing.** Hangar only, by maintainer choice.
- **No Node.js toolchain.** See Decision D3.

## 4. Verified facts

Every claim below was checked against a primary source. These facts drive the
design; several contradict what the repository currently documents.

| # | Fact | Source |
|---|---|---|
| F1 | Mojang moved to year-based versions in 2026: `26.1` is the first drop of 2026, `26.2` the second. So `26.2` is **newer** than `1.21.11`, not a rebrand of `1.21.x`. Real order: `1.21.10` → `1.21.11` → `26.1` → `26.1.2` → `26.2` → `26.3-pre`. | PaperMC news, Paper 26.2 "Chaos Cubed" (2026-06-16) |
| F2 | Paper publishes two coexisting Maven version schemes: legacy `1.x-R0.1-SNAPSHOT` (ends at `1.21.11`) and current `26.x.build.N-{alpha,beta,stable}`. `26.2.build.123-stable` exists and resolves. | `repo.papermc.io` `maven-metadata.xml` |
| F3 | GriefPrevention `16.18.7` (2026-03-05, latest published release) declares "Compatible with Spigot and derivatives 1.21.10+" and pins `spigot-api`/`paper-api` to `1.21.10-R0.1-SNAPSHOT`. It resolves from JitPack (HTTP 200). | GitHub release notes + `pom.xml` at tag `16.18.7` |
| F4 | GriefPrevention `master` is `18.0.0-SNAPSHOT` and targets `spigot-api:26.2-R0.1-SNAPSHOT` with `paper-api:[26.2.build,)`. So GriefPrevention **does** support 26.x on master; the published release notes and the Hangar listings are simply stale. | `pom.xml` on `master` |
| F5 | GriefPrevention tags `17.0.0` and `18.0.0` exist but have **no published release** (GitHub API returns 404). The project README states 16.x ("Legacy") is the line recommended for production and that 17+ carries breaking changes unsuitable for production. | GitHub tags API + README |
| F6 | Hangar's GriefPrevention listings are stale: the newest version listed is `16.18.4`, declaring `PAPER: 1.20.6-1.21`. No listed version declares 26.x. | `hangar.papermc.io` API |
| F7 | Adventure 5 (shipped in Paper 26.2) **retains** `LegacyComponentSerializer.legacyAmpersand()` and `deserialize(String)`. What it removed is unrelated: `BookMeta` no longer extends `Book`, and deprecated `ClickEvent`/`HoverEvent` usage. `Messages.java` is unaffected. | Adventure 5.2.0 javadoc |
| F8 | Hangar's official auto-publishing path requires Gradle, but `benwoo1110/hangar-upload-action@v1` uploads **already-built jars** and is therefore build-tool agnostic. Maven can stay. | PaperMC docs + action README |
| F9 | `release-please` has a `maven` strategy that updates `pom.xml` automatically and leaves a `-SNAPSHOT` version after each release. It supports non-default branches via `target-branch`, one workflow per branch. | release-please `docs/customizing.md` |
| F10 | The plugin cannot currently be built on the maintainer's machine: no `mvn` binary, no `mvnw` wrapper, no `~/.m2`, and the installed JDK is 17 while `pom.xml` requires `maven.compiler.release=21`. | Local environment audit |
| F13 | PaperMC's own requirements table: `1.20`–`1.21.11` require **Java 21**; **`26.1+` requires Java 25**. Starting a 26.x server on Java 21 or older fails with `UnsupportedClassVersionError`. | PaperMC docs "Getting started" |
| F14 | GriefPrevention `16.18.7`'s `plugin.yml` declares `api-version: '1.21.10'`. Bukkit treats `api-version` as a **minimum**: a newer server still loads the plugin, an older server refuses it. This is the mechanism by which GP 16.18.7 loads on Paper 26.2. | `src/main/resources/plugin.yml` at tag `16.18.7` |
| F12 | `release-please` supports `prerelease: true` with `prerelease-type` (`rc`/`beta`/`alpha`) combined with `target-branch`, and each branch keeps its own release history and manifest. The docs give **no** gitflow guidance. Open issue **#2476**: merge commits produce **duplicate CHANGELOG entries**. | release-please `manifest-releaser.md` + issue #2476 |
| F11 | The maintainer reports running the plugin successfully on a Paper 26.2 server with "the latest" GriefPrevention. The exact GriefPrevention build was not confirmed. The latest *published* release is `16.18.7`, which matches the pin at `pom.xml:43`. | Maintainer report — **empirical, exact version unconfirmed** |

### Contradictions in the current repository

F3 and F11 together expose three statements that cannot all be right:

1. `README.md` promises "Paper 1.21+", but GriefPrevention requires `1.21.10+`.
   A server on `1.21.0`–`1.21.9` cannot run GriefPrevention, and therefore
   cannot run ClaimBarter — yet the README invites it to try.
2. `plugin.yml:4` declares `api-version: '1.21'` while `pom.xml:38` compiles
   against Paper `26.2`.
3. `README.md` does not mention Spigot at all, but the plugin cannot run on it
   (see Decision D6).

The compatibility matrix is the artifact that resolves all three.

## 5. Decisions

### D1 — Branching: three tiers with fast-forward promotion

```
feature/*, fix/*  ──(squash)──►  dev
                                  │  ff-only
                                  ▼
                               staging  ──►  CI publishes v1.1.0-rc.N
                                  │            ├─ GitHub prerelease
                                  │            └─ Hangar channel "Snapshot"
                                  │  ff-only
                                  ▼
                                main  ──►  release-please  ──►  v1.1.0
                                                                └─ Hangar channel "Release"
```

| Branch | Role | Lifetime |
|---|---|---|
| `feature/*`, `fix/*` | Individual units of work | Short-lived, deleted after squash-merge |
| `dev` | Integration of finished work | Long-lived |
| `staging` | Pre-release; installable release candidates | Long-lived |
| `main` | Reflects exactly what is published as stable | Long-lived |

**Promotion is fast-forward only** (`git merge --ff-only`). This is a hard
requirement, not a preference. Two constraints force it:

1. Promotions **cannot be squashed.** Squashing `dev` into `staging` would
   collapse every conventional commit into one, destroying the granularity
   `release-please` needs to compute the next version.
2. Promotions **must not create merge commits**, because per F12 merge commits
   trigger release-please issue #2476 and duplicate CHANGELOG entries.

Fast-forward satisfies both: `dev`, `staging`, and `main` all sit on a single
linear history at different positions, so promotion moves a pointer and creates
no commit at all. Feature branches still squash-merge into `dev`, which is what
produces the clean one-commit-per-change history that makes fast-forward
possible.

**Consequence to accept:** `dev` must never diverge from `staging`, and
`staging` must never diverge from `main`. Nothing may be committed directly to
`staging` or `main` — including hotfixes, which start on a branch off `dev` like
anything else. If a fast-forward is ever refused, that is the signal that
something was committed out of band, and it must be resolved by rebasing rather
than by merging.

### D1b — `release-please` runs on `main` only

`staging` produces real, installable pre-releases, but **not** through
release-please. CI builds them and versions them as `-rc.<run_number>` off the
current `pom.xml` version, publishing to GitHub as a prerelease and to the
Hangar `Snapshot` channel.

**Rejected:** running release-please on both `staging` and `main` with
`prerelease: true`. Per F12 that gives each branch its own manifest, so every
promotion drags `staging`'s manifest into `main` as a recurring conflict, and
`main`'s CHANGELOG ends up carrying both a `1.1.0-rc.1` section and a `1.1.0`
section listing the same commits. Keeping release-please on `main` alone leaves
one manifest, one changelog, and one source of version truth, while `staging`
still delivers testable artifacts.

### D2 — Versioning: `release-please` with the `maven` strategy

Conventional commits reaching `main` drive semver. release-please runs on
`main` only (D1b). `release-please` opens a Release PR
that bumps `pom.xml` and `CHANGELOG.md`; merging it tags and creates the GitHub
Release (F9).

Configuration: `release-please-config.json` and `.release-please-manifest.json`.

`plugin.yml` already consumes `${project.version}` through Maven resource
filtering (`pom.xml:52-57`), so the jar version and the version the server
reports stay synchronised with no extra wiring. Builds off `dev` report
`1.1.0-SNAPSHOT` and release candidates off `staging` report `1.1.0-rc.N`, so an
administrator can tell at a glance which tier a jar came from.

`CHANGELOG.md` is generated. It must never be hand-edited.

### D3 — Commit conventions: Conventional Commits, enforced at the PR title

Types: `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `build`, `ci`,
`chore`. Breaking changes via `!` or a `BREAKING CHANGE:` footer.

Scopes, derived from the actual package layout: `barter`, `config`, `messages`,
`command`, `gp`, `build`.

**Enforcement mechanism:** squash-merge is required on all pull requests
targeting `dev`, and the PR title is linted with
`amannn/action-semantic-pull-request`. Promotions between `dev`, `staging`, and
`main` are fast-forward only and are never squashed (D1).

**Rejected:** `commitlint` + `husky`. Both require a `package.json` and
`node_modules` inside a pure Maven repository — an entire foreign ecosystem
pulled in to validate strings. Because squash-merge makes the PR title the
commit message that `release-please` actually reads, linting the title validates
the exact lever that matters, with zero new dependencies.

### D4 — Distribution: GitHub Releases + Hangar

Hangar is PaperMC's official plugin repository and therefore the maintainer's
exact audience. Publishing uses `benwoo1110/hangar-upload-action@v1`, which
uploads a prebuilt jar and does not require Gradle (F8):

```yaml
files: '[{"path": "target/ClaimBarter-*.jar", "platforms": ["PAPER"]}]'
```

**One-time manual setup, outside CI:** create the project on Hangar, create
**both** the `Release` and `Snapshot` channels, generate an API token with the
`create_version` permission, and store it as the repository secret
`HANGAR_API_TOKEN`. Stable releases go to `Release`; release candidates from
`staging` go to `Snapshot`.

### D5 — Add the Maven Wrapper

`mvnw` / `mvnw.cmd` / `.mvn/wrapper/`.

This is not cosmetic. Per F10 the project cannot be built on the maintainer's
own machine today. With the wrapper, `./mvnw package` works without a
system-wide Maven install, and CI pins the same Maven version a contributor
uses.

### D6 — Compatibility matrix lives in `SECURITY.md`

The matrix is authoritative in `SECURITY.md` at the maintainer's request.

`SECURITY.md` carries **two clearly separated tables**, because they answer
different questions for different readers:

1. **Supported Versions** — which ClaimBarter releases still receive security
   fixes. This is GitHub's conventional `SECURITY.md` table.
2. **Compatibility Matrix** — which server and GriefPrevention versions the
   plugin runs on.

`README.md` gets a **one-line pointer with a link**, not a copy. Rationale: an
administrator evaluating the plugin should not have to open the security policy
to learn whether it runs on their server, but two tables would inevitably
diverge. One source of truth, one pointer.

### D7 — Compatibility matrix content

Every cell carries an explicit status. Nothing is asserted that was not checked.

**Server software**

| Server | Version | Status |
|---|---|---|
| Paper / Purpur | `26.2` | Tested |
| Paper / Purpur | `26.1`, `26.1.x` | Untested, expected to work |
| Paper / Purpur | `1.21.10` – `1.21.11` | Untested |
| Paper | `< 1.21.10` | Not supported — GriefPrevention requires `1.21.10+` |
| Spigot | any | **Not supported** |

The Spigot row is a finding, not a footnote. `Messages.java:19-20` uses Adventure
(`LegacyComponentSerializer`) and delivers output through
`CommandSender.sendMessage(Component)`. Paper bundles Adventure; Spigot does not.
GriefPrevention runs on Spigot, so an administrator can reasonably assume
ClaimBarter does too. It does not, and the current README never says so.

**GriefPrevention**

| Version | Status |
|---|---|
| `16.18.7` | Compiled against; reported working on Paper 26.2 (F11), and loadable there by mechanism — it declares `api-version: '1.21.10'`, which Bukkit treats as a minimum (F14) |
| `16.18.0` – `16.18.6` | Untested |
| `17.x` / `18.x` | Not supported — no published release, breaking changes, not production-ready (F5) |

**Java**

| Purpose | Version | Source |
|---|---|---|
| Building ClaimBarter | 21 or newer | `maven.compiler.release=21` (`pom.xml:20`) |
| Running on Paper `26.1+` | **25** | F13 |
| Running on Paper `1.21.10`–`1.21.11` | 21 | F13 |

Targeting Java 21 bytecode is correct and must be preserved. Java is forward
compatible, so a 21-bytecode jar runs on a Java 25 JVM. That means one artifact
covers both a `1.21.11` server (Java 21) and a `26.2` server (Java 25). Raising
`maven.compiler.release` to 25 would lock the plugin out of the entire `1.21.x`
line for no gain.

### D8 — `SECURITY.md` threat model

ClaimBarter's real vulnerability classes are not the web-application defaults.
They are:

- **Item duplication** — an item returned without the claim blocks being debited.
- **Claim block minting** — blocks granted without the item being taken.
- **Permission bypass** — trading without `claimbarter.buy` / `claimbarter.sell`.
- **Item loss** — items taken with no blocks granted and no refund.

This is worth stating explicitly because the code already defends against it
deliberately: `BarterService.java:15-21` documents the transaction ordering so
that a crash costs the plugin rather than the server.

Out of scope: server misconfiguration, operator-granted bonus blocks being sold
(a documented behaviour, controlled by `selling.enabled`), and economy balance
complaints.

Reporting goes through **GitHub Private Vulnerability Reporting**, not an email
address.

### D9 — `ROADMAP.md` grounded in documented limitations

No invented promises. Every item derives from a limitation the current README
already documents as deliberate, under "What it deliberately does not do":

1. Multiple currency items (today a single `currency.item`).
2. Search the offhand and armor slots (today only `getStorageContents()`).
3. Accept items carrying metadata under an explicit opt-in.
4. Track the GriefPrevention 17.x/18.x line once it has a stable release (F5).
5. Decide a policy for the exact `paper-api` pin — `26.2.build.123-stable`
   (`pom.xml:38`) ages on its own.

### D11 — Recommended: raise `api-version` to `1.21.10` (requires approval)

`plugin.yml:4` declares `api-version: '1.21'`, which is **lower than the
`1.21.10` declared by GriefPrevention itself** (F14). Because Bukkit treats
`api-version` as a minimum, raising ClaimBarter's value to `'1.21.10'` would
make the server refuse to load the plugin on `1.21.0`–`1.21.9` — precisely the
range where GriefPrevention cannot run anyway. The compatibility matrix would
stop being documentation an administrator has to read and become a constraint
the platform enforces.

**Status: not included in this change.** It edits
`src/main/resources/plugin.yml`, and "no changes to `src/`" is a stated
non-goal. Listed here so the maintainer can approve it as a separate one-line
change.

### D10 — Supporting files

- `.github/dependabot.yml` — Maven and GitHub Actions update checks.
- README badges — CI status, latest release, license, Hangar downloads.
- `CODE_OF_CONDUCT.md` — Contributor Covenant.

## 6. File manifest

**New**

```
.github/workflows/ci.yml
.github/workflows/prerelease.yml
.github/workflows/release-please.yml
.github/workflows/release.yml
.github/ISSUE_TEMPLATE/bug_report.yml
.github/ISSUE_TEMPLATE/feature_request.yml
.github/ISSUE_TEMPLATE/config.yml
.github/PULL_REQUEST_TEMPLATE.md
.github/dependabot.yml
release-please-config.json
.release-please-manifest.json
CONTRIBUTING.md
SECURITY.md
ROADMAP.md
CODE_OF_CONDUCT.md
mvnw
mvnw.cmd
.mvn/wrapper/maven-wrapper.properties
```

**Modified**

```
README.md      — badges, compatibility pointer, corrected version claims, contributing link
.gitignore     — allow .mvn/wrapper, ignore .mvn/wrapper/maven-wrapper.jar if downloaded
```

**Generated, never hand-edited**

```
CHANGELOG.md   — produced by release-please
```

**Untouched**

```
src/**         — no code changes
pom.xml        — version managed by release-please only; no manual edits in this change
LICENSE
```

## 7. Workflow specifications

### `ci.yml`

- **Triggers:** `pull_request`; `push` to `dev`, `staging`, and `main`.
- **Steps:** checkout; set up JDK 21 (`temurin`) with Maven cache;
  `./mvnw -B verify`; upload `target/*.jar` as a workflow artifact.
- **Purpose:** the sole quality gate — it proves the project compiles and
  packages. It runs no tests, because there are none.

### `prerelease.yml`

- **Trigger:** `push` to `staging`.
- **Steps:** checkout; set up JDK 21; set the candidate version **in the
  workspace only** with
  `./mvnw -B versions:set -DnewVersion=<base>-rc.${{ github.run_number }} -DgenerateBackupPoms=false`;
  `./mvnw -B package`; create a GitHub release marked `prerelease: true`;
  publish to Hangar with channel `Snapshot`.
- **Why `versions:set` and not `-Drevision`:** the `revision` property only
  works when the POM declares its version as `${revision}`. `pom.xml:11` holds a
  literal version, so `-Drevision` would be silently ignored and the artifact
  would ship with the wrong version.
- **Constraint:** this workflow must not commit anything. It never touches
  `pom.xml` in the repository, `.release-please-manifest.json`, or
  `CHANGELOG.md` — otherwise `staging` diverges from `main` and the
  fast-forward promotion in D1 becomes impossible.

### `release-please.yml`

- **Trigger:** `push` to `main`.
- **Steps:** run `googleapis/release-please-action` with `release-type: maven`.
- **Result:** opens or updates a Release PR; merging it bumps `pom.xml`, writes
  `CHANGELOG.md`, tags, and creates the GitHub Release.

### `release.yml`

- **Trigger:** `release: published`.
- **Steps:** checkout the tag; set up JDK 21; `./mvnw -B package`; attach
  `target/ClaimBarter-*.jar` to the GitHub Release; publish to Hangar with
  `benwoo1110/hangar-upload-action@v1` using `HANGAR_API_TOKEN`, channel
  `Release`, and platform `PAPER`.

### PR title lint

- **Trigger:** `pull_request` (`opened`, `edited`, `synchronize`).
- **Action:** `amannn/action-semantic-pull-request` with the type and scope
  lists from D3.

## 8. Issue and PR templates

`bug_report.yml` requires, as mandatory fields:

- ClaimBarter version
- Output of `/version`
- Output of `/version GriefPrevention`
- Contents of `config.yml`
- Console log or paste link
- Steps to reproduce

These fields exist for a concrete reason: during this design the exact
GriefPrevention build running on the maintainer's own test server could not be
confirmed from memory (F11). Requiring the output rather than a recollection is
what prevents that gap in every future report.

`feature_request.yml`: problem, proposed behaviour, alternatives considered.

`config.yml`: `blank_issues_enabled: false`, with contact links to Discussions
and the Hangar page.

`PULL_REQUEST_TEMPLATE.md`: a Conventional Commits title reminder, a description
of the change, and a checklist including "tested on a real server — state the
Paper and GriefPrevention versions".

## 9. `CONTRIBUTING.md` outline

1. Branching model and where to target a PR (`dev` — never `staging`, never
   `main`), plus why promotions are fast-forward only.
2. Conventional Commits, with the type and scope lists.
3. Building: `./mvnw package`, JDK 21 or newer required.
4. Testing on a real server: drop the jar next to GriefPrevention, state the
   versions used.
5. PR expectations: squash-merge into `dev`, title lint, CI must pass.
6. Release process: `dev` → `staging` produces a release candidate,
   `staging` → `main` produces a stable release. Contributors never bump
   versions by hand and never commit to `staging` or `main`.

## 10. Resolved items

Both items open at design time were closed before implementation.

- **O1 — Which GriefPrevention build runs on the 26.2 test server. RESOLVED by
  mechanism.** The exact jar was not read off the server, but GP `16.18.7`
  declares `api-version: '1.21.10'`, and Bukkit treats that as a minimum, so
  Paper 26.2 loads it (F14). The maintainer's report of a working 26.2 server is
  therefore fully consistent with the latest published release, which is also
  what `pom.xml:43` pins. The matrix records `16.18.7` as supported with both the
  empirical report and the loading mechanism as evidence. No further action
  required; running `/version GriefPrevention` would still be the cheapest way
  to turn "consistent with" into "confirmed".
- **O2 — Runtime Java version for Paper 26.x. RESOLVED.** Paper `26.1+` requires
  **Java 25**; `1.20`–`1.21.11` require Java 21 (F13). Recorded in the D7 Java
  table, along with why the build must stay on Java 21 bytecode.

### Note on the local build environment

Per F10 the maintainer's machine has JDK 17 and no Maven. To build, JDK 21 or
newer is required; to run a 26.2 test server, Java 25 is required. The Maven
Wrapper (D5) removes the Maven half of that problem; the JDK half is a local
install the maintainer must do.

## 11. Approval

Design approved by the maintainer on 2026-09-10, with two amendments:

1. The compatibility matrix lives in `SECURITY.md` rather than `README.md` (D6).
2. Branching is three tiers — `main` ← `staging` ← `dev` ← `feature/*` — with
   fast-forward promotion and release-please on `main` only (D1, D1b).

Implementation authorised on 2026-09-10. One item awaits a separate decision:
raising `api-version` to `1.21.10` (D11).
