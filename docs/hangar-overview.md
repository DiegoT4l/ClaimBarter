<!--
  Hangar "Resource Page" overview for ClaimBarter.

  Paste the markdown below into Hangar -> Edit page. Everything used here was
  verified against Hangar's own renderer (marked 18 + marked-alert +
  marked-extended-tables + DOMPurify):

    works    GitHub alerts (> [!NOTE]), <details>/<summary>, aligned tables,
             fenced code with a language, YouTube link -> iframe embed,
             <div align="center">, image sizing ![alt](url==HEIGHTxWIDTH)
    broken   task lists (- [ ]) render as literal text
    quirk    markdown inside <details> is only parsed with blank lines around it
    quirk    every heading is demoted one level (# -> h2) and its anchor gets a
             leading dash, so do not hand-write in-page anchor links

  Keep this file and the Hangar page in sync; Hangar does not read the repo.
-->

GriefPrevention already ships `/buyclaimblocks` and `/sellclaimblocks`, but its
own `plugin.yml` describes both the same way: *"Doesn't work on servers without
a Vault-compatible economy plugin."* They move **server money**, a number on an
account.

If your server's currency is a physical item that lives in inventories and
chests, there is nothing for Vault to talk to, and installing an economy plugin
just to bridge the gap reintroduces the abstraction you were avoiding.

ClaimBarter skips the middleman. The item leaves the inventory, the claim blocks
land in GriefPrevention's bonus pool, and nothing is stored in between.

| | GriefPrevention's `/buyclaimblocks` | ClaimBarter |
| --- | --- | --- |
| Currency | Server money, an account balance | A physical item in the inventory |
| Vault + economy plugin | Required | Not required |
| Trading back | `/sellclaimblocks`, also Vault-bound | `/claimbarter sell`, at a configurable refund ratio |
| Where the blocks live | GriefPrevention's bonus pool | GriefPrevention's bonus pool |

No database, no telemetry, no shaded dependencies — the jar is 18 KB. GPL-3.0,
matching GriefPrevention.

## What players see

```text
> /claimbarter buy 10
[ClaimBarter] Bought 1000 claim blocks for 10 iron ingots.

> /claimbarter info
[ClaimBarter] Rate: 1 iron ingot = 100 claim blocks. You hold 1000 purchased and 1000 available.

> /claimbarter sell 500
[ClaimBarter] Sold 500 claim blocks back for 2 iron ingots.
```

Colours stripped for readability; every string above is editable in `config.yml`.

## Features

- **Any obtainable item can be the currency** — iron, netherite, a mob drop,
  whatever your server already treats as money.
- **One rule for the argument: it is always what you hand over.** `buy` counts
  items, `sell` counts blocks. No silent rounding.
- **A refund ratio that closes the loop.** Below `1.0`, buying and selling back
  is a loss, so the trade cannot be farmed.
- **Per-rank trading.** `claimbarter.buy` and `claimbarter.sell` are separate
  permissions, and tab completion only offers what the sender may actually run.
- **Crash-safe ordering.** A purchase takes the items first and grants blocks
  second; a sale removes and persists the blocks first and pays out second. A
  failure mid-transaction costs the plugin, never the server.
- **Config validated at load**, with the offending key and the reason in the
  console. The plugin disables itself rather than running on a configuration it
  cannot honour.
- **Hot reload** — `/claimbarter reload`, no restart.

## Requirements

| | Version |
| --- | --- |
| Server | Paper or Purpur `1.21.10` – `26.2` (tested on `26.2`) |
| GriefPrevention | `16.18.7` |
| Java | 21 on Paper `1.21.x`, 25 on Paper `26.x` |

> [!WARNING]
> **Spigot is not supported**, and this is an incompatibility rather than a gap
> in testing. Messages are rendered with Adventure and delivered through
> `sendMessage(Component)`. Paper bundles Adventure; Spigot does not.
> GriefPrevention itself does run on Spigot, so this is worth stating plainly.

GriefPrevention is declared as a hard `depend`, so ClaimBarter will not load
without it.

## Install

1. Drop the jar in `plugins/` next to GriefPrevention.
2. Restart. Editing `config.yml` afterwards needs only `/claimbarter reload`.

## Commands

| Command | Permission | Default |
| --- | --- | --- |
| `/claimbarter buy <items>` | `claimbarter.buy` | everyone |
| `/claimbarter sell <blocks>` | `claimbarter.sell` | everyone |
| `/claimbarter info` | — | everyone |
| `/claimbarter reload` | `claimbarter.reload` | operators |

Aliases: `/barter`, `/cbarter`. `reload` is the only subcommand that works from
the console; the other three are player-only.

**One rule for the argument: it is always what you hand over.** You buy with
items, so `buy` counts items. You sell claim blocks, so `sell` counts blocks.
This avoids the rounding trap where asking for 150 blocks at 100-per-ingot
silently charges two ingots and pockets the difference.

## Configuration

The generated `config.yml`, minus the `messages:` block:

```yaml
currency:
  item: IRON_INGOT       # any obtainable Material
  item-plural: ""        # empty derives it from item; set it for mass nouns
  blocks-per-item: 100   # 100 blocks is a 10x10 area

selling:
  enabled: true
  refund-ratio: 0.5      # 1.0 refunds fully; below 1.0 makes buy/sell loops a loss

limits:
  max-purchased-blocks: 0   # 0 means no ceiling
```

| Key | Default | Meaning |
| --- | --- | --- |
| `currency.item` | `IRON_INGOT` | Any obtainable `Material`. Air and non-items are refused. |
| `currency.item-plural` | *(derived)* | Name used next to a count above one. Empty derives it from `currency.item`, so changing the item cannot leave the messages describing a different one. The guess handles sibilants (`TORCH` → `torches`) and consonant + `y` (`POPPY` → `poppies`). Set it explicitly for a mass noun (`REDSTONE` wants `redstone`), a name already ending in `s` (`COMPASS` wants `compasses`), or one ending in `o` or `f` (`POTATO` wants `potatoes`). |
| `currency.blocks-per-item` | `100` | Claim blocks granted per item. 100 blocks is a 10×10 claim. |
| `selling.enabled` | `true` | `false` makes claim blocks a one-way purchase. |
| `selling.refund-ratio` | `0.5` | Fraction of the purchase price returned on a sale. Must be `0.0`–`1.0`. |
| `limits.max-purchased-blocks` | `0` | Ceiling on the bonus pool. `0` means no ceiling. |

> [!NOTE]
> `max-purchased-blocks` caps the **whole bonus pool**, including blocks an
> operator granted with `/adjustbonusclaimblocks` — ClaimBarter has no separate
> store of its own.

> [!WARNING]
> **Upgrading and you edited your messages?** `{item}` now matches the count
> printed beside it. If you previously wrote `{item}s` in a message to work
> around the missing plural, remove that trailing `s` or it will render
> `ingotss`. The plugin logs a warning at startup for every message it finds in
> that shape. Untouched messages need no change.

> [!IMPORTANT]
> Bad values are refused at load, not at the point of use. `currency.item` must
> be a real obtainable material, `blocks-per-item` must be greater than zero,
> `refund-ratio` must be between `0.0` and `1.0`, and `max-purchased-blocks`
> must not be negative. The console names the key and the reason, and the plugin
> disables itself.

<details>
<summary>Messages and placeholders</summary>

Every player-facing string lives under `messages:` in `config.yml` and uses `&`
colour codes. `prefix` is prepended to all of them.

| Key | Placeholders |
| --- | --- |
| `bought`, `sold` | `{blocks}`, `{items}`, `{item}` |
| `info` | `{item}`, `{blocks}`, `{purchased}`, `{available}` |
| `not-enough-items` | `{needed}`, `{have}`, `{item}` |
| `not-enough-blocks` | `{have}` |
| `blocks-in-use` | `{available}` |
| `amount-too-small` | `{item}` |
| `limit-reached` | `{limit}` |
| `items-lost` | `{lost}`, `{item}` |
| `selling-disabled`, `invalid-amount`, `overflow`, `no-permission`, `players-only`, `usage`, `reloaded`, `transaction-failed`, `data-unavailable` | none |

`saveDefaultConfig()` never overwrites an existing `config.yml`, so a server
upgrading from an older release keeps its own `messages:` block. A key that is
missing renders `Missing message: <key>` in red rather than failing silently.

</details>

## Design decisions

These are deliberate, not unfinished.

**Only plain items are spent.** A renamed, enchanted, or custom-model-data ingot
is skipped, not taken. Sharing a material with the currency should never cost a
player a keepsake or a quest item.

**Only the main inventory is searched.** Armor slots, the offhand, and ender
chests are not touched.

**Selling draws from GriefPrevention's bonus pool**, the same pool
`/adjustbonusclaimblocks` writes to. Blocks a player *accrued* by playing are
never sellable — that would turn idle time into an item faucet.

> [!WARNING]
> Because the bonus pool is shared, bonus blocks handed out by an operator
> **can** be cashed out for items. If you do not want that on your server, set
> `selling.enabled: false`.

**Blocks committed to existing claims cannot be sold.** Abandon the claim first.

## FAQ

<details>
<summary>Does it need Vault or an economy plugin?</summary>

No, and it is not planned. Supporting Vault is the opposite of the point — use
GriefPrevention's own `/buyclaimblocks` if you have an economy plugin already.

</details>

<details>
<summary>Does it store anything? Is there a database?</summary>

No. ClaimBarter writes only to GriefPrevention's bonus pool and reads only the
player's inventory. There is no data folder, no database, and no bStats or other
telemetry.

</details>

<details>
<summary>What is the smallest amount a player can sell?</summary>

Whatever returns at least one whole item, because the payout is floored. With
the defaults (`blocks-per-item: 100`, `refund-ratio: 0.5`) that is **200 claim
blocks**; anything less gets *"That is too small to return a single iron
ingot."*

The general rule is `blocks ≥ blocks-per-item ÷ refund-ratio`. Setting
`refund-ratio: 1.0` lowers the minimum to one item's worth of blocks.

</details>

<details>
<summary>What happens if the player's inventory is full?</summary>

Items that do not fit are dropped at the player's feet rather than lost. The
same applies to the refund path when a purchase fails halfway through.

One caveat worth knowing if you run an anti-lag or region plugin: a drop that
another plugin cancels destroys the stack, and nothing in the Bukkit API
reports that back at the call site. ClaimBarter checks whether each dropped
item actually exists, tells the player how many never arrived, and logs the
shortfall for you to restore.

</details>

<details>
<summary>Can I restrict trading to a rank?</summary>

Yes. `claimbarter.buy` and `claimbarter.sell` both default to `true`; set them
to `false` in your permissions plugin and grant them per rank. Tab completion
adapts, so a player who cannot sell is never offered `sell` in the first place.

</details>

<details>
<summary>Will it follow GriefPrevention 17.x / 18.x?</summary>

Not yet. GriefPrevention's `master` carries breaking changes, upstream does not
consider it production-ready, and there is no published release for the `17.0.0`
or `18.0.0` tags. ClaimBarter tracks the `16.x` line until that changes.

</details>

<details>
<summary>Something else I should know before installing?</summary>

- Purchases are priced in whole items and sales pay out in whole items; neither
  side gives change.
- `blocks-per-item` is a rate, not a cap — a single `buy 64` at the default rate
  grants 6400 claim blocks.
- The purchase ceiling check runs against the bonus pool *after* the trade, so a
  partially affordable purchase is refused outright rather than reduced.

</details>

## Source and support

- **Source:** https://github.com/DiegoT4l/ClaimBarter — six classes, no
  shaded dependencies, GPL-3.0.
- **Bugs and feature requests:** the
  [issue tracker](https://github.com/DiegoT4l/ClaimBarter/issues). Please
  include `/version`, `/version GriefPrevention`, and your `config.yml`.
- **Security:** item duplication, claim block minting, permission bypass, and
  item loss go through
  [private vulnerability reporting](https://github.com/DiegoT4l/ClaimBarter/security/advisories/new),
  not a public issue.
- **Roadmap:** [ROADMAP.md](https://github.com/DiegoT4l/ClaimBarter/blob/main/ROADMAP.md)
  lists what is under consideration and what is explicitly not planned.
