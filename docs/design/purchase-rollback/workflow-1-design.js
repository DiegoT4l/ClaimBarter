export const meta = {
  name: 'claimbarter-tx-design',
  description: 'Map the transaction failure space of BarterService with bytecode evidence, generate independent designs, attack them, and pick one',
  phases: [
    { title: 'Map', detail: 'three parallel readers: current code throw points, Bukkit semantics, GriefPrevention semantics' },
    { title: 'Design', detail: 'four independent transaction-protocol designs from distinct stances' },
    { title: 'Attack', detail: 'three lenses per design try to break it' },
    { title: 'Judge', detail: 'synthesize the winning spec and a failure-injection harness plan' },
  ],
}

const REPO = '/Users/diegotalamantes/Documents/projects/ClaimBarter'
const SERVICE = REPO + '/src/main/java/io/github/diegot4l/claimbarter/BarterService.java'
const GP_JAR = '/Users/diegotalamantes/.m2/repository/com/github/GriefPrevention/GriefPrevention/16.18.7/GriefPrevention-16.18.7.jar'

const KNOWN_FACTS = `
VERIFIED FACTS (all checked with javap against the jars in ~/.m2 during this session; treat as ground truth, re-verify only if you need more detail):
- GriefPrevention 16.18.7, DataStore.savePlayerData(UUID, PlayerData): entire body is \`new SavePlayerDataThread(this, uuid, data).start(); return;\`. It cannot throw on I/O. It throws NPE if the receiver is null; Thread.start() can throw OutOfMemoryError (an Error, not RuntimeException).
- DataStore.savePlayerDataSync(UUID, PlayerData) is public: calls data.getAccruedClaimBlocks(), data.getClaims(), then asyncSavePlayerData(uuid, data) directly on the calling thread. No thread is created.
- FlatFileDataStore.overrideSavePlayerData: reads data.getAccruedClaimBlocks() (offset 23) and data.getBonusClaimBlocks() (offset 43) AT SERIALIZATION TIME, i.e. off the live object when the save thread runs, not captured at call time. Writes via com.google.common.io.Files.write (offset 105) inside exception table 13..108 -> handler at 111 catching java.lang.Exception, which only logs and printStackTraces. So write failures are swallowed. This method is NOT synchronized; its siblings writeClaimToStorage and incrementNextClaimID ARE synchronized.
- PlayerData: field \`private Integer bonusClaimBlocks\` is NOT volatile. \`public int getBonusClaimBlocks()\` is NOT synchronized (contrast \`public synchronized int getAccruedClaimBlocks()\`). \`public void setBonusClaimBlocks(Integer)\` takes a boxed Integer, so every call site autoboxes via Integer.valueOf, which allocates for values outside -128..127. getBonusClaimBlocks() lazily calls loadDataFromSecondaryStorage() when the field is null, and that reads GriefPrevention.instance.dataStore internally.
- GriefPrevention.instance and GriefPrevention.instance.dataStore are public mutable fields that GriefPrevention clears while unloading.
- paper-api 26.2: World.dropItemNaturally(Location, ItemStack) returns org.bukkit.entity.Item (non-null) whether or not ItemSpawnEvent was cancelled; a cancelled spawn destroys the stack with no return signal. Entity.isValid() and isDead() exist. ItemSpawnEvent is fired synchronously into listeners, which may throw or teleport the player.
- Inventory.addItem(ItemStack...) returns Map<Integer, ItemStack> of what did NOT fit; it mutates the inventory slot by slot as it goes.
- MemorySection.getString(path, def) returns def when the value is absent or YAML null.
- The repository has NO test suite and no src/test. CI proves compilation only. Build needs JDK 25 (javac release 21). ./mvnw -B clean verify is the build; a non-clean verify may skip compilation silently.
- CLAUDE.md invariants: on buy, items are taken first then blocks granted; on sell, blocks removed and persisted first then items handed over; "a failure mid-transaction must cost the plugin, never the server"; only the bonus pool is ever written; NEVER hardcode a player-facing string in Java (all text in config.yml under messages:); message keys have NO defaults, so a new key renders "&cMissing message: <key>" on any server whose config.yml predates it (a user-visible change to call out).
- SECURITY.md classes item loss, item duplication, and claim-block minting as vulnerabilities, not bugs.
- The plugin is single-threaded on the Bukkit main thread for command handling; GriefPrevention's save threads are the only concurrency.
`

const REVIEW_HISTORY = `
FAILURES ALREADY FOUND IN FIVE REVIEW ROUNDS OF THIS BRANCH (a design that repeats any of these is disqualified):
1. Memory-only rollback: a concurrent GriefPrevention save serializing between the grant and the failure persists the granted total; restoring memory does not fix disk.
2. Refund placed behind a helper that caught only RuntimeException: an Error (OOM from Thread.start) skipped the refund -> item loss.
3. Async re-save as compensation: starts a second unsynchronized truncating writer racing the first; and on the documented path (save threw before starting its thread) nothing was written, so the log claiming "disk may hold N" was actively misleading.
4. sell() returned ok("sold") after the payout failed -> player told success, blocks gone, no items.
5. transaction-failed message said "nothing was charged" after items were taken and lost.
6. deliver() counted cancelled drops as delivered (dropItemNaturally gives no signal) -> silent item loss reported as success.
7. Relative rollback (getBonus - blocks) with the setter INSIDE the try: if the autoboxing setter itself throws, the mutation never happened but the rollback still applied -> minted blocks (sell) / negative pool (buy).
8. Absolute rollback (write back the snapshot read at the top) clobbers an operator /adjustbonusclaimblocks that landed in the window.
9. Log composed before the refund: string concatenation allocates; under OOM it aborted the unwind before the refund.
10. Missing-key fallback that appended placeholder values: hardcoded player-facing text in Java (forbidden) and spliced operator text into the template before substitution, bypassing quoting.
11. Location cached before the drop loop: an ItemSpawnEvent listener can teleport the player; later stacks drop in the wrong place/world.
12. deliver() did not count items addItem had already placed before it threw -> over-reports loss -> operator restores too many -> duplication.
13. Resolving GriefPrevention.instance.dataStore once up front was documented as preventing mid-transaction NPE, but PlayerData lazy-load re-reads the global internally; it is a narrowing, not a guarantee.
14. info() returned the transaction-failed key for a read-only lookup.
15. Rewording an existing message key does not reach upgraded servers (saveDefaultConfig never overwrites), so fixes to wording need new keys, which in turn render "Missing message" on old configs.
16. A one-time "NEW KEYS, copy them across" upgrade banner was baked permanently into the shipped default config.
17. Pre-boxing the rollback Integer was added to make the rollback allocation-free, then removed because deliver()/log allocate anyway; the reviewer then flagged the rollback autobox as an OOM hazard again. The two invariants (never mint; never lose items) have DIFFERENT allocation requirements and must be reasoned about separately.
`

const MAP_SCHEMA = {
  type: 'object',
  properties: {
    facts: { type: 'array', items: { type: 'object', properties: {
      claim: { type: 'string' },
      evidence: { type: 'string', description: 'file:line, javap offset, or command output that proves it' },
      relevance: { type: 'string', description: 'why a transaction-protocol designer must know this' },
    }, required: ['claim', 'evidence', 'relevance'] } },
    surprises: { type: 'array', items: { type: 'string' }, description: 'anything that contradicts KNOWN_FACTS or the review history' },
  },
  required: ['facts', 'surprises'],
}

const DESIGN_SCHEMA = {
  type: 'object',
  properties: {
    name: { type: 'string' },
    stance: { type: 'string' },
    buy_steps: { type: 'array', items: { type: 'string' }, description: 'ordered steps of buy(), each one atomic-ish, naming what is precomputed before any mutation' },
    sell_steps: { type: 'array', items: { type: 'string' } },
    failure_table: { type: 'array', items: { type: 'object', properties: {
      point: { type: 'string', description: 'exact step and exception type (RuntimeException / Error / silent)' },
      state_at_failure: { type: 'string', description: 'items taken? blocks mutated? saved? what is live' },
      handling: { type: 'string', description: 'exact compensation, in order' },
      player_sees: { type: 'string', description: 'message key and whether it is truthful' },
      operator_sees: { type: 'string', description: 'log level and content' },
      invariant_kept: { type: 'string', description: 'never-mint / never-lose-items / no-lies / diagnosable — which hold, which are best-effort, which are broken' },
    }, required: ['point', 'state_at_failure', 'handling', 'player_sees', 'operator_sees', 'invariant_kept'] } },
    delivery_accounting: { type: 'string', description: 'how the number of items that actually reached the player is computed, and what it does NOT capture' },
    message_keys: { type: 'array', items: { type: 'object', properties: { key: { type: 'string' }, new_or_existing: { type: 'string' }, text: { type: 'string' }, placeholders: { type: 'string' } }, required: ['key', 'new_or_existing', 'text', 'placeholders'] } },
    residual_risks: { type: 'array', items: { type: 'string' }, description: 'what this design explicitly does NOT guarantee, and why it is acceptable' },
    self_attack: { type: 'array', items: { type: 'string' }, description: 'the three most likely ways a hostile reviewer breaks this design, and your answer to each' },
    complexity_estimate: { type: 'string', description: 'approximate lines of Java in BarterService after the change' },
  },
  required: ['name', 'stance', 'buy_steps', 'sell_steps', 'failure_table', 'delivery_accounting', 'message_keys', 'residual_risks', 'self_attack', 'complexity_estimate'],
}

const ATTACK_SCHEMA = {
  type: 'object',
  properties: {
    lens: { type: 'string' },
    broken: { type: 'boolean', description: 'true if you found a scenario where the design breaks a hard invariant (mint / silent item loss / lie to player)' },
    scenarios: { type: 'array', items: { type: 'object', properties: {
      title: { type: 'string' },
      steps: { type: 'string', description: 'concrete sequence: state, action, failure injected, resulting state' },
      invariant_broken: { type: 'string' },
      severity: { type: 'string', enum: ['hard-invariant', 'degraded', 'cosmetic'] },
      fix_hint: { type: 'string' },
    }, required: ['title', 'steps', 'invariant_broken', 'severity', 'fix_hint'] } },
    strengths: { type: 'array', items: { type: 'string' } },
  },
  required: ['lens', 'broken', 'scenarios', 'strengths'],
}

const JUDGE_SCHEMA = {
  type: 'object',
  properties: {
    chosen_design: { type: 'string' },
    rationale: { type: 'string' },
    grafted_from_others: { type: 'array', items: { type: 'string' } },
    final_spec: { type: 'object', properties: {
      buy_steps: { type: 'array', items: { type: 'string' } },
      sell_steps: { type: 'array', items: { type: 'string' } },
      info_steps: { type: 'array', items: { type: 'string' } },
      failure_table: { type: 'array', items: { type: 'object', properties: {
        point: { type: 'string' }, handling: { type: 'string' }, player_sees: { type: 'string' }, operator_sees: { type: 'string' }, invariant_kept: { type: 'string' },
      }, required: ['point', 'handling', 'player_sees', 'operator_sees', 'invariant_kept'] } },
      delivery_accounting: { type: 'string' },
      message_keys: { type: 'array', items: { type: 'object', properties: { key: { type: 'string' }, new_or_existing: { type: 'string' }, text: { type: 'string' }, placeholders: { type: 'string' } }, required: ['key', 'new_or_existing', 'text', 'placeholders'] } },
      javadoc_claims_allowed: { type: 'array', items: { type: 'string' }, description: 'the ONLY guarantees the class javadoc may claim' },
      residual_risks: { type: 'array', items: { type: 'string' } },
    }, required: ['buy_steps', 'sell_steps', 'info_steps', 'failure_table', 'delivery_accounting', 'message_keys', 'javadoc_claims_allowed', 'residual_risks'] },
    harness_plan: { type: 'object', properties: {
      stubs_needed: { type: 'array', items: { type: 'string' }, description: 'each Bukkit/GP class to stub, with the knobs it needs (throw on Nth call, cancel spawn, null dataStore, partial addItem, etc.)' },
      injections: { type: 'array', items: { type: 'object', properties: {
        id: { type: 'string' }, description: { type: 'string' }, setup: { type: 'string' }, action: { type: 'string' },
        expect_items: { type: 'string' }, expect_blocks: { type: 'string' }, expect_result_key: { type: 'string' }, expect_log_contains: { type: 'string' },
      }, required: ['id', 'description', 'setup', 'action', 'expect_items', 'expect_blocks', 'expect_result_key', 'expect_log_contains'] } },
      invariants_checked_every_case: { type: 'array', items: { type: 'string' } },
    }, required: ['stubs_needed', 'injections', 'invariants_checked_every_case'] },
    unresolved_disagreements: { type: 'array', items: { type: 'string' }, description: 'points where designers/attackers disagreed and the evidence did not settle it; these go to the human' },
  },
  required: ['chosen_design', 'rationale', 'grafted_from_others', 'final_spec', 'harness_plan', 'unresolved_disagreements'],
}

const common = `You are working on the Paper plugin ClaimBarter at ${REPO}. Read CLAUDE.md there first. The branch fix/purchase-rollback is checked out. The file under design is ${SERVICE}. GriefPrevention 16.18.7 jar for javap: ${GP_JAR}. paper-api jar: find ~/.m2 -path "*paper-api*" -name "*.jar" ! -name "*sources*" | head -1. Use javap -p -c to verify any bytecode claim you rely on; do not trust memory. Do NOT modify any file in the repository. Your final output is data for an orchestrator, not prose for a human.

${KNOWN_FACTS}
${REVIEW_HISTORY}`

phase('Map')
log('Mapping the failure space from three angles')
const maps = (await parallel([
  () => agent(`${common}

TASK: Enumerate EVERY point in BarterService.buy(), sell(), info(), dataStore(), persistRollback(), deliver(), removeCurrency(), countCurrency() as the file stands NOW where control can leave the method abnormally (RuntimeException, Error, or a silent wrong outcome), and for each, what state is live: items taken? block field mutated? save started? Include the exact line numbers. Also enumerate every state mutation and what it touches (player inventory vs PlayerData live field vs disk). Be exhaustive: read the whole file, not just the transaction methods. Flag anything the current code claims in a comment that the code does not actually guarantee.`,
    { label: 'map:current-code', phase: 'Map', schema: MAP_SCHEMA, effort: 'high' }),
  () => agent(`${common}

TASK: Establish, with javap evidence from the paper-api jar, the exact semantics a transaction designer needs from Bukkit: Inventory.addItem partial behaviour and what it mutates before returning; PlayerInventory.setStorageContents / getStorageContents (copy or live?); World.dropItemNaturally return and cancellation behaviour; Entity.isValid/isDead; Player.getLocation (fresh object or cached?); ItemStack constructor/setAmount validity limits; Material.getMaxStackSize. Also: can any of these throw on a plain server, and which of them dispatch events synchronously into third-party listeners? State what is NOT knowable from the API jar (implementation-defined in CraftBukkit) and say so.`,
    { label: 'map:bukkit', phase: 'Map', schema: MAP_SCHEMA, effort: 'high' }),
  () => agent(`${common}

TASK: Re-verify and extend the GriefPrevention facts with javap on the jar: DataStore.getPlayerData (does it allocate/cache? can it return null? does it read the global?), PlayerData.loadDataFromSecondaryStorage (what it dereferences), SavePlayerDataThread.run (what it calls, any locking), DataStore.asyncSavePlayerData, DataStore.savePlayerDataSync (exact call sequence), PlayerData.getRemainingClaimBlocks (does it read bonusClaimBlocks? synchronized?), and where else in GriefPrevention savePlayerData is called (PlayerEventHandler etc.) so a designer knows how often unrelated saves of the same PlayerData happen. Also check: does GriefPrevention's own /buyclaimblocks handler do ANY unwinding on failure? Quote what it does. Confirm or refute each item in KNOWN_FACTS.`,
    { label: 'map:griefprevention', phase: 'Map', schema: MAP_SCHEMA, effort: 'high' }),
])).filter(Boolean)

const MAP_TEXT = JSON.stringify(maps, null, 1)
log(`Map done: ${maps.reduce((n, m) => n + m.facts.length, 0)} facts, ${maps.reduce((n, m) => n + m.surprises.length, 0)} surprises`)

phase('Design')
const STANCES = [
  { key: 'minimal', stance: 'MINIMAL COMPENSATION. Find the smallest change to the ORIGINAL dev version (giveCurrency first in catch, absolute rollback, catch RuntimeException) that satisfies the hard invariants. Be suspicious of every helper and every extra path; each new line is a new failure mode. Accept documented residual risk over clever compensation.' },
  { key: 'precommit', stance: 'PRE-COMMIT EVERYTHING. Compute and allocate every value the failure path will ever need BEFORE the first mutation (pre-boxed Integers for both the grant and the rollback, log strings, ItemStacks for the refund). Make the critical section between "items taken" and "blocks persisted" as close to allocation-free and throw-free as the API allows, and reason precisely about which invariant each pre-allocation protects.' },
  { key: 'statemachine', stance: 'EXPLICIT STATE. Track exactly which mutation landed (itemsTaken, blocksMutated, saveStarted) with flags set immediately after each succeeds, and compensate ONLY what landed, in reverse order. Every compensation step is itself guarded and its own failure is recorded. Errors are rethrown after compensation.' },
  { key: 'measure', stance: 'MEASURE, DO NOT TRUST. Never trust a return value or a flag for what happened to items or blocks: count the currency in the inventory before and after every hand-over, read the live block field after every write, and derive what to report from observed state. Design the delivery accounting so that a cancelled drop, a throwing listener, and a partial addItem all produce the correct "items that reached the player" number.' },
]

const designs = (await parallel(STANCES.map(s => () => agent(`${common}

FAILURE-SPACE MAP produced by three readers (ground truth for this design):
${MAP_TEXT}

YOUR STANCE: ${s.stance}

TASK: Produce a complete transaction-protocol design for buy(), sell() and info() under your stance. It must satisfy: (1) never mint claim blocks the server did not receive payment for; (2) never report success to the player when items or blocks did not actually move as stated; (3) never tell the player something false; (4) every failure path leaves an operator-diagnosable SEVERE line naming the player, the quantities, and what is left to fix by hand; (5) no player-facing text in Java; (6) no sync file I/O on the success path (sync write on the rollback path is permitted); (7) it must not repeat any numbered item in the review history. Fill the failure table for EVERY throw point in the map, including Error paths and silent paths (swallowed write, cancelled spawn). State residual risks honestly - a design that claims to guarantee what GriefPrevention's API cannot support is disqualified. Do NOT write Java yet; describe steps precisely enough that a writer could implement them without decisions.`,
  { label: `design:${s.key}`, phase: 'Design', schema: DESIGN_SCHEMA, effort: 'xhigh' })))).filter(Boolean)

log(`${designs.length} designs produced`)

phase('Attack')
const LENSES = [
  { key: 'mint', lens: 'SERVER LOSS. Find any sequence where the design leaves the bonus pool higher than it should be (minted blocks) or lets a player sell blocks twice. Inject: setter throws (autobox OOM), save throws NPE, save throws OOM, concurrent GP save serializes mid-window, rollback write fails, Error during compensation.' },
  { key: 'loss', lens: 'PLAYER LOSS AND LIES. Find any sequence where the player ends with fewer items AND fewer blocks than before, or is told a message that is false for the actual state. Inject: addItem partial then throw, dropItemNaturally cancelled, listener throws mid-payout, listener teleports player, Error inside deliver, refund never runs because something before it threw.' },
  { key: 'ops', lens: 'OPERATOR DIAGNOSABILITY AND UPGRADE. For every failure, is the SEVERE line sufficient and TRUTHFUL (no claim about disk contents that is not known, no "refunded N" before the refund ran, no over-reported loss that causes duplication on manual restore)? Is any player text hardcoded in Java? Do new message keys handle the upgraded-server case honestly? Does info() ever emit a trade-failure message? Can a player flood the log?' },
]

const attacks = await parallel(designs.map(d => () =>
  parallel(LENSES.map(l => () => agent(`${common}

FAILURE-SPACE MAP:
${MAP_TEXT}

DESIGN UNDER ATTACK:
${JSON.stringify(d, null, 1)}

YOUR LENS: ${l.lens}

TASK: Break this design. Construct concrete scenarios (state -> action -> injected failure -> resulting state) and say which hard invariant each one breaks. Default to broken=true if you find ANY hard-invariant scenario. Be adversarial but honest: a scenario that requires the JVM to be so broken that nothing could work is 'degraded', not 'hard-invariant'. Also list what the design gets RIGHT that others might not.`,
    { label: `attack:${d.name.slice(0, 18)}:${l.key}`, phase: 'Attack', schema: ATTACK_SCHEMA, effort: 'xhigh', model: 'sonnet' })))
  .then(vs => ({ design: d, attacks: vs.filter(Boolean) }))))

const scored = attacks.filter(Boolean).map(a => ({
  name: a.design.name,
  hard: a.attacks.reduce((n, v) => n + v.scenarios.filter(s => s.severity === 'hard-invariant').length, 0),
  degraded: a.attacks.reduce((n, v) => n + v.scenarios.filter(s => s.severity === 'degraded').length, 0),
  brokenVotes: a.attacks.filter(v => v.broken).length,
}))
log('Attack tally: ' + scored.map(s => `${s.name}: ${s.hard} hard / ${s.degraded} degraded / ${s.brokenVotes}/3 broken-votes`).join(' | '))

phase('Judge')
const judge = await agent(`${common}

FAILURE-SPACE MAP:
${MAP_TEXT}

ALL DESIGNS WITH THEIR ATTACKS:
${JSON.stringify(attacks, null, 1)}

ATTACK TALLY: ${JSON.stringify(scored)}

TASK: You are the final judge. Pick the design with the fewest hard-invariant breaks, then graft in any strength from the others that closes a remaining scenario without reopening a numbered item from the review history. Produce the FINAL SPEC precisely enough that a single writer can implement it with no decisions left, including the exact ordering in buy() and sell(), the full failure table, the delivery accounting algorithm, the exact message keys and texts (English, neutral, no hardcoding in Java), and the ONLY guarantees the class javadoc is allowed to claim. Then produce a HARNESS PLAN: a stubbed Bukkit + GriefPrevention surface (the repo has no test suite and the build needs the real API jars only for compilation; a standalone harness under a scratch directory compiling InvalidSettingException + BarterSettings + BarterService + Messages against hand-written stubs is the known-working approach) with knobs to inject each failure, and a list of injections with expected items / blocks / result key / log. Every injection must assert the same core invariants: items conserved or shortfall reported truthfully; blocks never above the correct value; result key truthful for the final state. List any disagreement the evidence did not settle - those go to the human, not to you.`,
  { label: 'judge:final-spec', phase: 'Judge', schema: JUDGE_SCHEMA, effort: 'xhigh', model: 'opus' })

return { maps, designs, attacks, scored, judge }