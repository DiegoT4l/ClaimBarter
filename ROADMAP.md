# Roadmap

ClaimBarter is feature-complete for the problem it was built to solve. This
roadmap lists changes under consideration, not commitments, and nothing here has
a date.

The README section "What it deliberately does not do" describes limitations that
are intentional. Items below are the ones worth revisiting; anything in that
section not listed here is staying as it is.

## Under consideration

### Multiple currency items

Today `currency.item` accepts exactly one `Material`. Servers with tiered
economies would want several, each with its own `blocks-per-item` rate. The open
question is what `/claimbarter buy 10` means when more than one currency is
configured.

### Search the offhand and armor slots

Currency is counted from the main inventory only. Extending the search is
straightforward; the reason to be careful is that armor and offhand items are
easy to lose track of, so this would likely be opt-in.

### Accept items carrying metadata, behind an explicit opt-in

Renamed, enchanted, and custom-model-data items are skipped rather than spent,
so a keepsake that shares a material with the currency is never taken by
accident. Some servers use custom-model-data items as currency on purpose and
need the opposite behaviour. This would be a separate config flag, never the
default.

### Follow the GriefPrevention 17.x/18.x line

GriefPrevention's `master` carries breaking changes and upstream does not
consider it production-ready, and there is no published release for the `17.0.0`
or `18.0.0` tags. ClaimBarter tracks the `16.x` line until that changes.

### Decide a policy for the Paper API pin

`pom.xml` pins an exact Paper build. That is good for reproducible builds and
bad for staying current, since the pin ages on its own. Options are periodic
manual bumps or a build-range pin; neither has been chosen.

## Not planned

- **Vault or economy plugin support.** The entire point of this plugin is not
  needing one. Use GriefPrevention's own `/buyclaimblocks` for that.
- **Selling accrued claim blocks.** Only the bonus pool is sellable. Letting
  players cash out blocks earned by playing would turn idle time into an item
  faucet.
- **Spigot support.** Message rendering uses Adventure, which Paper bundles and
  Spigot does not.
