# ClaimBarter

Players hand over a real item and receive GriefPrevention claim blocks. No
Vault, no economy plugin, no balances.

```
/claimbarter buy 10      →  10 iron ingots leave the inventory, 1000 claim blocks arrive
/claimbarter sell 500    →  500 claim blocks are given back, iron is returned at the refund rate
/claimbarter info        →  the current rate and what you hold
```

## Why this exists

GriefPrevention already ships `/buyclaimblocks`, but it goes through Vault and
calls `depositPlayer(player, double)` — it needs an account balance. If your
server's currency is a physical item that lives in inventories and chests,
there is nothing for Vault to talk to, and installing an economy plugin just to
bridge the gap reintroduces the abstraction you were avoiding.

ClaimBarter skips it. The item leaves the inventory, the claim blocks land in
GriefPrevention's bonus pool. Nothing is stored in between.

## Install

1. Drop the jar in `plugins/` next to GriefPrevention.
2. Restart. Editing `config.yml` afterwards needs only `/claimbarter reload`.

Requires **GriefPrevention 16.18.7+** and **Paper 1.21+**. Declared as a hard
dependency, so the plugin will not load without it.

## Commands

| Command | Permission | Default |
| --- | --- | --- |
| `/claimbarter buy <items>` | `claimbarter.buy` | everyone |
| `/claimbarter sell <blocks>` | `claimbarter.sell` | everyone |
| `/claimbarter info` | — | everyone |
| `/claimbarter reload` | `claimbarter.reload` | operators |

Aliases: `/barter`, `/cbarter`.

**One rule for the argument: it is always what you hand over.** You buy with
items, so `buy` counts items. You sell claim blocks, so `sell` counts blocks.
This avoids the rounding trap where asking for 150 blocks at 100-per-ingot
silently charges two ingots and pockets the difference.

## Configuration

```yaml
currency:
  item: IRON_INGOT       # any obtainable Material
  blocks-per-item: 100   # 100 blocks is a 10x10 area

selling:
  enabled: true
  refund-ratio: 0.5      # 1.0 refunds fully; below 1.0 makes buy/sell loops a loss

limits:
  max-purchased-blocks: 0   # 0 means no ceiling
```

Every message is in `config.yml` under `messages:` and uses `&` colour codes.

Bad values are refused at load with a specific reason in the console, and the
plugin disables itself rather than running on a configuration it cannot honour.

## What it deliberately does not do

- **Only plain items count.** A renamed or enchanted ingot is skipped, not
  spent. Sharing a material with the currency should not cost a player a
  keepsake or a quest item.
- **Only the main inventory is searched** — not armor slots, not the offhand,
  not ender chests.
- **Selling draws from GriefPrevention's bonus pool**, which is the same pool
  `/adjustbonusclaimblocks` writes to. If operators hand out bonus blocks and
  you do not want those cashed out for items, set `selling.enabled: false`.
  Blocks a player *accrued* by playing are never sellable — that would turn
  idle time into an item faucet.
- **Blocks committed to existing claims cannot be sold.** You have to abandon
  a claim first.

## Notes for anyone reading the code

`PlayerData.setBonusClaimBlocks()` does not persist on its own.
`DataStore.savePlayerData()` has to be called afterwards or the change is lost
on the next reload — GriefPrevention says so in a comment at `DataStore.java`
and it is the single easiest way to write a plugin like this that appears to
work and quietly eats people's items.

Transaction ordering is deliberate: a purchase takes the items first and grants
blocks second; a sale removes and persists the blocks first and hands over
items second. A crash in the middle costs the plugin, never the server.

## Building

```
mvn clean package
```

Java 21 or newer. GriefPrevention comes from JitPack, Paper from the PaperMC
repository; both are `provided` and are not shaded into the jar (18 KB).

## License

GPL-3.0, matching GriefPrevention, whose API this links against.
