export const meta = {
  name: 'claimbarter-tx-implement-verify',
  description: 'Implement the judged transaction spec in BarterService, build a failure-injection harness, run it to green, then adversarially review until dry',
  phases: [
    { title: 'Build', detail: 'writer implements the spec; harness builder builds stubs + 27 injections in parallel', model: 'opus / sonnet' },
    { title: 'Harness', detail: 'run against the working tree; triage each failure as code-bug or harness-bug; fix; repeat' },
    { title: 'Review', detail: 'three adversarial lenses + spec conformance + harness adequacy; fix; repeat until dry' },
  ],
}

const REPO = '/Users/diegotalamantes/Documents/projects/ClaimBarter'
const PKG = REPO + '/src/main/java/io/github/diegot4l/claimbarter'
const SERVICE = PKG + '/BarterService.java'
const TASKS = '/private/tmp/claude-505/-Users-diegotalamantes-Documents-projects-ClaimBarter/e0771929-81b0-4f67-9b97-2b2a0e79b897/tasks'
const SPEC = TASKS + '/final_spec.json'
const PLAN = TASKS + '/harness_plan.json'
const HARNESS = '/private/tmp/claude-505/-Users-diegotalamantes-Documents-projects-ClaimBarter/e0771929-81b0-4f67-9b97-2b2a0e79b897/scratchpad/harness'
const GP_JAR = '/Users/diegotalamantes/.m2/repository/com/github/GriefPrevention/GriefPrevention/16.18.7/GriefPrevention-16.18.7.jar'

const common = `Paper plugin ClaimBarter at ${REPO}, branch fix/purchase-rollback checked out (HEAD a73a4d2, clean). Read ${REPO}/CLAUDE.md first and obey it: Allman braces, package-private classes, Javadoc that explains WHY, no player-facing string in Java, message keys have no defaults. Additional hard rules for this task: src/main/java must stay pure ASCII (use \\u00A7 escapes, hyphens not dashes); config.yml, Messages.java, BarterSettings.java, BarterCommand.java, ClaimBarterPlugin.java and InvalidSettingException.java must NOT be modified - the spec requires ZERO new message keys and ZERO config changes; do not commit, do not push, do not touch git except read-only commands. The build is \`cd ${REPO} && ./mvnw -B clean verify\` (JDK 25 is on PATH; ALWAYS use clean - a non-clean verify can skip compilation and print BUILD SUCCESS). javap the GriefPrevention jar at ${GP_JAR} to verify any bytecode claim you rely on. Your final output is data for an orchestrator; return exactly the requested structure.`

const WRITE_SCHEMA = { type: 'object', properties: {
  build_ok: { type: 'boolean' },
  build_tail: { type: 'string', description: 'last 15 lines of the mvnw output' },
  line_count: { type: 'integer' },
  deviations: { type: 'array', items: { type: 'object', properties: { step: { type: 'string' }, what: { type: 'string' }, why: { type: 'string' } }, required: ['step', 'what', 'why'] }, description: 'every place the implementation departs from final_spec.json, however small; empty if none' },
  javadoc_deletions_done: { type: 'array', items: { type: 'string' } },
  notes: { type: 'string' },
}, required: ['build_ok', 'build_tail', 'line_count', 'deviations', 'javadoc_deletions_done', 'notes'] }

const HARNESS_SCHEMA = { type: 'object', properties: {
  harness_dir: { type: 'string' },
  run_command: { type: 'string', description: 'single shell command that copies the four sources fresh from the repo WORKING TREE, compiles everything, runs all injections under both inventory semantics, and prints a machine-readable summary line per injection: "RESULT <id> <mirror|copy> PASS|FAIL <assertion-or-empty>" and a final "TOTAL passed=N failed=M"' },
  compiles_against_committed: { type: 'boolean', description: 'true if the harness compiles and runs against `git show HEAD:...` copies of the four sources (the committed a73a4d2 state), used as the baseline' },
  baseline: { type: 'object', properties: { passed: { type: 'integer' }, failed: { type: 'integer' }, sample_failures: { type: 'array', items: { type: 'string' } } }, required: ['passed', 'failed', 'sample_failures'] },
  injections_implemented: { type: 'array', items: { type: 'string' } },
  injections_skipped: { type: 'array', items: { type: 'object', properties: { id: { type: 'string' }, why: { type: 'string' } }, required: ['id', 'why'] } },
  knobs_unimplemented: { type: 'array', items: { type: 'string' } },
  notes: { type: 'string' },
}, required: ['harness_dir', 'run_command', 'compiles_against_committed', 'baseline', 'injections_implemented', 'injections_skipped', 'knobs_unimplemented', 'notes'] }

const RUN_SCHEMA = { type: 'object', properties: {
  compiles: { type: 'boolean' },
  compile_error: { type: 'string' },
  passed: { type: 'integer' }, failed: { type: 'integer' }, total: { type: 'integer' },
  failures: { type: 'array', items: { type: 'object', properties: { id: { type: 'string' }, semantics: { type: 'string' }, assertion: { type: 'string' }, observed: { type: 'string' } }, required: ['id', 'semantics', 'assertion', 'observed'] } },
  raw_tail: { type: 'string', description: 'last 40 lines of harness output' },
}, required: ['compiles', 'compile_error', 'passed', 'failed', 'total', 'failures', 'raw_tail'] }

const TRIAGE_SCHEMA = { type: 'object', properties: {
  decisions: { type: 'array', items: { type: 'object', properties: {
    id: { type: 'string' }, semantics: { type: 'string' },
    verdict: { type: 'string', enum: ['code-bug', 'harness-bug', 'spec-gap'] },
    reasoning: { type: 'string', description: 'cite the spec step and the code line or harness line that decides it' },
    fix_instruction: { type: 'string', description: 'exact change for the fixer, naming file and location' },
  }, required: ['id', 'semantics', 'verdict', 'reasoning', 'fix_instruction'] } },
  spec_gaps_for_human: { type: 'array', items: { type: 'string' } },
}, required: ['decisions', 'spec_gaps_for_human'] }

const FIX_SCHEMA = { type: 'object', properties: {
  build_ok: { type: 'boolean' },
  files_changed: { type: 'array', items: { type: 'string' } },
  summary: { type: 'string' },
  refused: { type: 'array', items: { type: 'object', properties: { instruction: { type: 'string' }, why: { type: 'string' } }, required: ['instruction', 'why'] }, description: 'fix instructions you declined because they would violate the spec or CLAUDE.md' },
}, required: ['build_ok', 'files_changed', 'summary', 'refused'] }

const REVIEW_SCHEMA = { type: 'object', properties: {
  lens: { type: 'string' },
  findings: { type: 'array', items: { type: 'object', properties: {
    title: { type: 'string' },
    severity: { type: 'string', enum: ['hard-invariant', 'spec-deviation', 'degraded', 'cosmetic'] },
    file: { type: 'string' }, line: { type: 'integer' },
    scenario: { type: 'string', description: 'concrete state -> action -> injected failure -> observed wrong outcome' },
    harness_gap: { type: 'string', description: 'if the harness should have caught this, which injection or assertion is missing' },
    fix_hint: { type: 'string' },
  }, required: ['title', 'severity', 'file', 'line', 'scenario', 'harness_gap', 'fix_hint'] } },
}, required: ['lens', 'findings'] }

const ADEQUACY_SCHEMA = { type: 'object', properties: {
  vacuous_assertions: { type: 'array', items: { type: 'object', properties: { where: { type: 'string' }, why_vacuous: { type: 'string' }, fix: { type: 'string' } }, required: ['where', 'why_vacuous', 'fix'] } },
  stub_infidelities: { type: 'array', items: { type: 'object', properties: { stub: { type: 'string' }, real_behaviour: { type: 'string' }, stub_behaviour: { type: 'string' }, impact: { type: 'string' } }, required: ['stub', 'real_behaviour', 'stub_behaviour', 'impact'] } },
  missing_injections: { type: 'array', items: { type: 'string' } },
  verdict: { type: 'string', enum: ['trustworthy', 'needs-fixes', 'unreliable'] },
}, required: ['vacuous_assertions', 'stub_infidelities', 'missing_injections', 'verdict'] }

// ---------------------------------------------------------------- Build
phase('Build')
log('Writer (opus) implements the spec while the harness builder (sonnet) builds stubs + injections')
const [written, harness] = await parallel([
  () => agent(`${common}

ROLE: implementation writer.

Read ${SPEC} in full. It is the judged, attacked, synthesized transaction protocol for BarterService: buy_steps B1-B12 and U1-U7, sell_steps S1-S12 and SU1-SU6, info_steps I1-I7, delivery_accounting, message_keys (ALL EXISTING - add none), javadoc_claims_allowed (the ONLY guarantees the class javadoc may state, plus MANDATORY DELETIONS of three current sentences), residual_risks. Then read the current ${SERVICE} (511 lines).

TASK: Rewrite ${SERVICE} so that it implements the spec EXACTLY, step for step, with NO decisions of your own. Where the spec names a helper (warmClaimData, Handover, deliver(player, amount, context, Handover), persistRollback returning Throwable, dataStore(operation, player)), implement it as specified. Preserve countCurrency, isPlainCurrency, removeCurrency bodies unless the spec changes them (it does not). Every guarded step gets its own try/catch(Throwable) as the spec says; every ledger flag is set on the line AFTER the call it records; the summary SEVERE is composed LAST from measured values and is compositional (append every applicable fix clause). The class javadoc must contain the allowed claims and the mandatory non-guarantees, and must NOT contain the three sentences the spec orders deleted. Javadoc explains WHY, not what. Keep the file ASCII-only.

Then run the build with clean verify and report. If a spec step is impossible to implement as written (API does not exist, contradiction), implement the closest faithful thing, and record it in deviations with the exact reason - do not silently improvise. Return the structure requested.`,
    { label: 'build:writer', phase: 'Build', schema: WRITE_SCHEMA, model: 'opus', effort: 'xhigh' }),

  () => agent(`${common}

ROLE: harness builder. You will NOT modify the repository at all.

Read ${PLAN} in full: stubs_needed (13 stubs with their knobs), injections (27, each with setup/action/expected items/blocks/result key/log), invariants_checked_every_case (10, asserted on EVERY injection). Read ${SPEC} for the protocol the injections test. Read the four sources you must compile: ${PKG}/InvalidSettingException.java, ${PKG}/BarterSettings.java, ${PKG}/Messages.java, ${PKG}/BarterService.java - for the harness build use the COMMITTED versions via \`git -C ${REPO} show HEAD:src/main/java/io/github/diegot4l/claimbarter/<File>.java\` so a writer editing the working tree in parallel cannot break your compile; the run_command you produce must instead copy from the WORKING TREE, because that is what will be tested later.

TASK: Build a standalone failure-injection harness under ${HARNESS} (create it). Layout: ${HARNESS}/stubs/<package dirs>/*.java for every stub in the plan with EVERY knob listed; ${HARNESS}/driver/Harness.java (in package io.github.diegot4l.claimbarter so it can call the package-private API and construct BarterSettings via its record constructor) that: installs a recording java.util.logging.Handler; loads the shipped message texts from ${REPO}/src/main/resources/config.yml (parse the messages: block yourself - simple key: "value" lines - do not add a YAML library); runs EVERY injection twice (mirrorSemantics true and false); asserts ALL TEN invariants on every run plus the injection's own expectations; prints one line per run exactly as "RESULT <id> <mirror|copy> PASS|FAIL <first failed assertion text or empty>" and a final "TOTAL passed=N failed=M". Also produce ${HARNESS}/run.sh that: copies the four sources fresh from the repo working tree into ${HARNESS}/src, compiles stubs + src + driver with javac --release 21 into ${HARNESS}/out, and runs the driver. It must exit non-zero if any run fails or compilation fails. Stubs must model the verified bytecode faithfully: getClaims() assigns the list FIRST then applies fixNegativeDeficit once only if claims was null; getString(path, def) returns def for absent AND null; savePlayerData/Sync record the LIVE pool value at call time; pendingStaleWriter captures the live pool at setBonusClaimBlocks time and flushStaleWriter writes it to simulatedDisk later; PlayerData.setterThrows is ARMED in every run and the harness asserts it never fired and that every setter argument on the pre-boxed branch is identical (==) to an Integer created before the first mutation. Make the interleaved call log a single ordered list across ALL stubs so ordering invariants are asserted from it, not from source.

Then run the harness against the COMMITTED sources as a baseline. It is EXPECTED that many injections FAIL against the committed code (that code is what the spec replaces); a harness that passes everything against the old code is a broken harness. Report compile status, baseline counts, which injections you implemented, which you had to skip and exactly why, and any knob you could not implement.`,
    { label: 'build:harness', phase: 'Build', schema: HARNESS_SCHEMA, model: 'sonnet', effort: 'xhigh' }),
])

if (!written) throw new Error('writer returned nothing')
if (!harness) throw new Error('harness builder returned nothing')
log(`Writer: build_ok=${written.build_ok}, ${written.line_count} lines, ${written.deviations.length} deviations. Harness: compiles=${harness.compiles_against_committed}, baseline ${harness.baseline.passed} pass / ${harness.baseline.failed} fail, ${harness.injections_skipped.length} skipped`)

// ---------------------------------------------------------------- Harness loop
phase('Harness')
const runHarness = (round) => agent(`${common}

ROLE: harness runner. Run exactly this command and report its output faithfully: \`${harness.run_command}\`. If it fails to compile, put the compiler error in compile_error and set compiles=false. Parse every "RESULT ..." line into failures (only FAIL lines) and the TOTAL line into counts. Do not fix anything. Do not interpret. Round ${round}.`,
  { label: `harness:run-${round}`, phase: 'Harness', schema: RUN_SCHEMA, model: 'sonnet', effort: 'low' })

let run = await runHarness(1)
const fixLog = []
for (let round = 1; round <= 4 && (!run.compiles || run.failed > 0); round++) {
  log(`Harness round ${round}: compiles=${run.compiles}, ${run.passed} pass / ${run.failed} fail`)
  const triage = await agent(`${common}

ROLE: triage judge. The spec at ${SPEC} is AUTHORITATIVE; the harness plan at ${PLAN} defines what each injection must assert. The implementation is ${SERVICE} (working tree). The harness is under ${HARNESS} (stubs/, driver/Harness.java, run.sh).

HARNESS RESULT (round ${round}):
${JSON.stringify(run, null, 1)}

WRITER'S DECLARED DEVIATIONS FROM SPEC:
${JSON.stringify(written.deviations, null, 1)}

TASK: For EVERY failure (and for a compile error, treat it as one failure), read the relevant spec step, the relevant code, and the relevant harness assertion, and decide: code-bug (the code departs from the spec), harness-bug (the assertion or stub is wrong against the spec or against the verified bytecode - a stub that does not model GriefPrevention faithfully is a harness bug), or spec-gap (the spec is ambiguous or contradictory here; describe it for the human and choose the reading that keeps the hard invariants: never mint, never silently lose items, never lie to the player). Write a precise fix_instruction for each - file, location, exact change. Prefer fixing the code over relaxing an assertion; relax an assertion ONLY when you can cite the spec or bytecode that proves the assertion wrong.`,
    { label: `harness:triage-${round}`, phase: 'Harness', schema: TRIAGE_SCHEMA, model: 'opus', effort: 'xhigh' })

  const fix = await agent(`${common}

ROLE: fixer. Apply these triage decisions exactly. You may edit ${SERVICE} for code-bug decisions and files under ${HARNESS} for harness-bug decisions. For spec-gap decisions apply the fix_instruction as written (the triage judge already chose the invariant-preserving reading). Do NOT edit any other repository file. After editing, run \`cd ${REPO} && ./mvnw -B clean verify\` and confirm BUILD SUCCESS, and confirm src/main/java is still pure ASCII (\`LC_ALL=C grep -rn '[^\\x00-\\x7F]' ${PKG}\` must print nothing). Refuse (and record why) any instruction that would add a message key, edit config.yml, hardcode player-facing text in Java, or contradict the spec.

TRIAGE:
${JSON.stringify(triage, null, 1)}`,
    { label: `harness:fix-${round}`, phase: 'Harness', schema: FIX_SCHEMA, model: 'opus', effort: 'high' })

  fixLog.push({ round, triage, fix })
  run = await runHarness(round + 1)
}
log(`Harness final: compiles=${run.compiles}, ${run.passed} pass / ${run.failed} fail after ${fixLog.length} fix rounds`)

// ---------------------------------------------------------------- Review loop
phase('Review')
const LENSES = [
  { key: 'mint', lens: 'SERVER LOSS. Read the implemented code, not the spec. Find any sequence where the bonus pool ends higher than the correct value, a deduction is made durable without payout, a grant is refunded beside a live grant, or a rollback writes a value that was not measured. Inject in your head: setter throws, save throws NPE/OOM, concurrent GP save mid-window, persistRollback throws, Error inside any guarded step, pool moved between snapshot and undo.' },
  { key: 'loss', lens: 'PLAYER LOSS AND LIES. Read the implemented code, not the spec. Find any sequence where the player ends with fewer items AND fewer blocks than the truthful outcome, or receives a Result whose message is false for the actual state, or where a measured count is wrong (addItem partial then throw, cancelled spawn, shrinking/merging listener, teleport, kick mid-payout, drop throws with the stack in flight, Error inside deliver, countCurrency throws in the diff).' },
  { key: 'ops', lens: 'OPERATOR TRUTH. For every SEVERE/WARNING/INFO the code can emit: is it composed from MEASURED values only, does it ever claim the file was written, does it ever say "took back"/"gave back"/"undone" when the ledger flag is false, does the compositional fix clause include EVERY applicable clause, is the exact /adjustbonusclaimblocks command present whenever a pool mutation was applied, is any player-facing text hardcoded in Java, can info() ever return a trade key, can the class javadoc be read as promising something the bytecode does not support (check every sentence against the allowed-claims list in the spec).' },
]
const reviewLog = []
for (let round = 1; round <= 2; round++) {
  const reviews = (await parallel([
    ...LENSES.map(l => () => agent(`${common}

Spec (authoritative): ${SPEC}. Implementation under review: ${SERVICE} (working tree). Harness: ${HARNESS}. Latest harness result: ${JSON.stringify({ compiles: run.compiles, passed: run.passed, failed: run.failed })}.

YOUR LENS: ${l.lens}

TASK: Break the implementation. Read every line of BarterService.java. For each finding give a concrete scenario and, if the harness should have caught it, name the missing injection or assertion. Severity 'hard-invariant' only for mint / silent item loss / false message to player; 'spec-deviation' when the code departs from a numbered spec step; otherwise 'degraded' or 'cosmetic'. Do not report the residual risks the spec already lists as accepted unless the code makes one of them WORSE than the spec allows.`,
      { label: `review-${round}:${l.key}`, phase: 'Review', schema: REVIEW_SCHEMA, model: 'sonnet', effort: 'xhigh' })),
    () => agent(`${common}

Spec (authoritative): ${SPEC}. Implementation: ${SERVICE} (working tree).

ROLE: spec-conformance auditor. Go through EVERY numbered step (B1..B12, U1..U7, S1..S12, SU1..SU6, I1..I7, the delivery_accounting algorithm, message_keys, javadoc_claims_allowed including the MANDATORY DELETIONS and MANDATORY NON-GUARANTEES) and check the code implements it exactly. Report every departure as a finding with severity 'spec-deviation' (or 'hard-invariant' if the departure breaks never-mint / never-lose / never-lie). Quote the spec text and the code line. Also verify: no file other than BarterService.java changed (\`git -C ${REPO} status --short\`), src/main/java is pure ASCII, Allman braces throughout.`,
      { label: `review-${round}:conformance`, phase: 'Review', schema: REVIEW_SCHEMA, model: 'sonnet', effort: 'xhigh' }),
    () => agent(`${common}

Harness plan (authoritative): ${PLAN}. Harness implementation: ${HARNESS} (read stubs/, driver/Harness.java, run.sh). Verified bytecode facts are in the plan's stub descriptions; re-verify with javap where a stub's fidelity matters.

ROLE: harness adequacy critic. A green harness is only evidence if its assertions can fail. For each of the ten invariants and each injection: is the assertion actually evaluated against observed state, or is it tautological / always-true / comparing a value to itself? Does each stub model the REAL behaviour (getClaims assigns-then-fixes once; getString default on null; dropItemNaturally returns non-null Item on cancel; addItem mutates before returning; savePlayerData records the live value at call time; pendingStaleWriter flushes AFTER the sync write)? Are both inventory semantics really exercised? Is PlayerData.setterThrows really armed? Is the interleaved call log really shared across stubs? List every injection from the plan that is missing or weakened. Verdict: trustworthy / needs-fixes / unreliable.`,
      { label: `review-${round}:harness-adequacy`, phase: 'Review', schema: ADEQUACY_SCHEMA, model: 'sonnet', effort: 'xhigh' }),
  ])).filter(Boolean)

  const lensFindings = reviews.filter(r => r.findings).flatMap(r => r.findings.map(f => ({ ...f, lens: r.lens })))
  const adequacy = reviews.find(r => r.verdict)
  const blocking = lensFindings.filter(f => f.severity === 'hard-invariant' || f.severity === 'spec-deviation')
  const adequacyBlocking = adequacy && adequacy.verdict !== 'trustworthy'
  log(`Review round ${round}: ${lensFindings.length} findings (${blocking.length} blocking), harness adequacy=${adequacy ? adequacy.verdict : 'n/a'}`)
  reviewLog.push({ round, findings: lensFindings, adequacy })
  if (!blocking.length && !adequacyBlocking) break
  if (round === 2) break

  const triage = await agent(`${common}

Spec: ${SPEC}. Plan: ${PLAN}. Code: ${SERVICE}. Harness: ${HARNESS}.

REVIEW FINDINGS (blocking only):
${JSON.stringify(blocking, null, 1)}

HARNESS ADEQUACY REPORT:
${JSON.stringify(adequacy, null, 1)}

TASK: Triage as before: for each finding decide code-bug / harness-bug / spec-gap with a precise fix_instruction; for each vacuous assertion, stub infidelity, or missing injection in the adequacy report, write a harness fix_instruction (use id = the invariant or injection name, semantics = 'harness'). Reject findings that merely restate an accepted residual risk from the spec without showing the code makes it worse - say so in reasoning and give fix_instruction 'none'.`,
    { label: `review-${round}:triage`, phase: 'Review', schema: TRIAGE_SCHEMA, model: 'opus', effort: 'xhigh' })

  const fix = await agent(`${common}

ROLE: fixer. Apply these decisions exactly (skip any with fix_instruction 'none'). Code fixes go in ${SERVICE} only; harness fixes under ${HARNESS}. Then run \`cd ${REPO} && ./mvnw -B clean verify\` (must succeed), confirm ASCII purity of ${PKG}, and run \`${harness.run_command}\` and include its TOTAL line in your summary. Refuse and record anything that would add a message key, edit config.yml, hardcode player text in Java, or contradict the spec.

DECISIONS:
${JSON.stringify(triage, null, 1)}`,
    { label: `review-${round}:fix`, phase: 'Review', schema: FIX_SCHEMA, model: 'opus', effort: 'high' })
  reviewLog[reviewLog.length - 1].triage = triage
  reviewLog[reviewLog.length - 1].fix = fix
  run = await runHarness(100 + round)
  log(`Post-review harness: compiles=${run.compiles}, ${run.passed} pass / ${run.failed} fail`)
}

return { written, harness, harnessFinal: run, fixLog, reviewLog }