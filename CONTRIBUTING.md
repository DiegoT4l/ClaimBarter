# Contributing to ClaimBarter

ClaimBarter exists to solve one problem: trading a physical item for
GriefPrevention claim blocks without Vault. Contributions that keep it that
narrow are the easiest to accept.

## Branches

```
feature/*, fix/*  --(squash)-->  dev  --(ff-only)-->  staging  --(ff-only)-->  main
```

| Branch | What it is |
| --- | --- |
| `dev` | Where finished work is integrated. **Target your pull request here.** |
| `staging` | Release candidates. Published automatically as `-rc.N` prereleases. |
| `main` | Exactly what is released as stable. |

Two rules make this work, and both are strict:

1. **Never commit directly to `staging` or `main`.** Hotfixes start on a branch
   off `dev` like everything else.
2. **Promotions are fast-forward only** (`git merge --ff-only`).

The second rule is not stylistic. Promotions cannot be squashed, because that
would collapse every conventional commit into one and destroy the information
`release-please` uses to compute the next version. Promotions also must not
create merge commits, because `release-please` duplicates changelog entries when
it encounters them. Fast-forward is the only strategy that satisfies both. If a
fast-forward is ever refused, something was committed out of band - rebase, do
not merge.

## Commit messages

This project uses [Conventional Commits](https://www.conventionalcommits.org/).
The version number and changelog are generated from them, so the format is
enforced by CI.

Pull requests are squash-merged, which means **your PR title becomes the commit
message**. That title is what gets linted.

```
type(scope): subject
```

Types: `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`

Scopes (optional): `barter`, `config`, `messages`, `command`, `gp`, `build`

The subject starts lowercase and does not end with a period. A breaking change
is marked with `!` after the type or scope, or with a `BREAKING CHANGE:` footer.

```
feat(config): support multiple currency items
fix(barter): refund items when saving player data fails
docs: correct the supported Paper version range
feat(barter)!: price sales in items rather than blocks
```

## Building

You need **JDK 21 or newer**. You do not need Maven installed - use the wrapper.

```bash
./mvnw clean package
```

The jar lands in `target/ClaimBarter-<version>.jar`. Both dependencies are
`provided` and are not shaded in, so the jar stays small.

## Testing your change

There is no automated test suite. CI proves only that the project compiles and
packages, so **testing on a real server is your job**, and the pull request asks
you to state what you tested on.

1. Set up a Paper server. Check the compatibility matrix in
   [SECURITY.md](SECURITY.md) for supported versions - note that Paper `26.1+`
   requires Java 25 to run, while Paper `1.21.x` requires Java 21.
2. Install GriefPrevention. It is a hard dependency; ClaimBarter will not load
   without it.
3. Drop your jar in `plugins/` and start the server.
4. Exercise the paths your change touches. At minimum: `/claimbarter buy`,
   `/claimbarter sell`, `/claimbarter info`, and `/claimbarter reload`.
5. Restart the server and confirm claim block totals survived. Persistence bugs
   in this plugin are silent - `PlayerData.setBonusClaimBlocks()` does not save
   on its own, and forgetting `DataStore.savePlayerData()` produces a plugin
   that looks like it works while quietly eating players' items.

Record the versions you used with `/version` and `/version GriefPrevention`.

## Pull requests

- Target `dev`.
- One logical change per pull request.
- Keep the title conventional; it is the commit message.
- CI must pass.
- Fill in the "Tested on" section. A pull request that changes trade logic
  without it will be asked for it.

## Releases

You never bump a version by hand. `release-please` reads the conventional
commits on `main`, opens a release pull request that updates `pom.xml` and
`CHANGELOG.md`, and tags on merge. `CHANGELOG.md` is generated - do not edit it.

Merging `dev` into `staging` publishes a release candidate. Merging `staging`
into `main` starts a stable release.
