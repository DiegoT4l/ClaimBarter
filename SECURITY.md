# Security Policy

## Supported Versions

Security fixes are applied to the latest minor release only. There are no
long-term support branches.

| ClaimBarter | Supported |
| --- | --- |
| 1.x (latest minor) | Yes |
| Older minors | No - upgrade to the latest |
| Release candidates (`-rc.N`) | No - pre-release builds are not patched |

## Compatibility

This is the authoritative compatibility matrix for ClaimBarter. The README links
here rather than duplicating it.

### Server software

| Server | Version | Status |
| --- | --- | --- |
| Paper / Purpur | `26.2` | Tested |
| Paper / Purpur | `26.1`, `26.1.x` | Untested, expected to work |
| Paper / Purpur | `1.21.10` - `1.21.11` | Untested |
| Paper | below `1.21.10` | Not supported - GriefPrevention requires `1.21.10+` |
| Spigot | any | **Not supported** |

Spigot is not a gap in testing, it is an incompatibility. ClaimBarter renders its
messages with Adventure and delivers them through `sendMessage(Component)`.
Paper bundles Adventure; Spigot does not. GriefPrevention itself does run on
Spigot, so this is worth stating plainly rather than leaving to inference.

### GriefPrevention

| Version | Status |
| --- | --- |
| `16.18.7` | Supported. ClaimBarter is compiled against it, and it is reported working on Paper 26.2. It declares `api-version: '1.21.10'`, which Bukkit treats as a minimum, so a newer server still loads it. |
| `16.18.0` - `16.18.6` | Untested |
| `17.x` / `18.x` | Not supported. No published release, breaking changes, and upstream does not consider these production-ready. |

### Java

| Purpose | Version |
| --- | --- |
| Building ClaimBarter | 21 or newer |
| Running on Paper `26.1+` | 25 |
| Running on Paper `1.21.10` - `1.21.11` | 21 |

ClaimBarter ships Java 21 bytecode deliberately. Java is forward compatible, so
one jar runs on both a `1.21.11` server (Java 21) and a `26.2` server (Java 25).

## What counts as a vulnerability

ClaimBarter moves items and claim blocks between a player's inventory and
GriefPrevention's bonus pool. The security surface is economic, so these are in
scope:

- **Item duplication** - items returned without the claim blocks being debited.
- **Claim block minting** - claim blocks granted without the items being taken.
- **Permission bypass** - trading without `claimbarter.buy` or `claimbarter.sell`.
- **Item loss** - items taken with no blocks granted and no refund.

Transaction ordering is a deliberate defence against this class of bug: a
purchase takes the items first and grants blocks second, while a sale removes and
persists the blocks first and hands over items second. A failure mid-transaction
is meant to cost the plugin, never the server. Reports that defeat that ordering
are treated as security issues, not as ordinary bugs.

Out of scope:

- Server misconfiguration, including a `blocks-per-item` or `refund-ratio` that
  makes trading uneconomical.
- Players selling bonus blocks granted by operators via
  `/adjustbonusclaimblocks`. This is documented behaviour; disable it with
  `selling.enabled: false`.
- Griefing or claim disputes, which are GriefPrevention's domain.

## Reporting

Report vulnerabilities privately through GitHub's
[Private Vulnerability Reporting](https://github.com/DiegoT4l/ClaimBarter/security/advisories/new).
Please do not open a public issue for anything in the "What counts as a
vulnerability" list above.

Include the same version details the bug report form asks for: the output of
`/version` and `/version GriefPrevention`, your `config.yml`, and reproduction
steps.

Expect an initial response within 7 days. If a report is confirmed, the fix ships
in the next patch release and the advisory is published once the release is out.
