# Purchase rollback redesign (PR #16) - working artifacts

Design record for the `BarterService.buy()` / `sell()` failure-path redesign.
These are orchestration artifacts, not documentation for plugin users. They are
committed so the work can be resumed from another machine; drop or move them
before the PR is merged if they should not land in `dev`.

## What is here

| File | What it is |
| --- | --- |
| `maps.json` | 67 verified facts about the failure space (BarterService throw points, Bukkit semantics, GriefPrevention 16.18.7 bytecode), with evidence per fact. |
| `judge.json` | The judge's full output: chosen design (`explicit-state-ledger`), rationale, eight grafts from the other three designs, the final spec, the harness plan, and eight **unresolved decisions for the maintainer**. |
| `final_spec.json` | `judge.final_spec` extracted: buy/sell/info steps, failure table, delivery accounting, message keys (none added), allowed javadoc claims, residual risks. The implementation contract. |
| `harness_plan.json` | `judge.harness_plan` extracted: 13 stubs with knobs, 27 failure injections x 2 inventory semantics, 10 invariants asserted on every run. The verification contract. |
| `workflow-1-design.js` | The workflow that produced the above (map -> 4 designs -> 12 attacks -> judge). Run id `wf_d9da989c-7b0`. |
| `workflow-2-implement-verify.js` | The workflow that implements the spec, builds the harness, runs it to green, and reviews until dry. First run `wf_8697fa39-eb6` was stopped before writing anything; it was rerun on 2026-09-24 as `wf_06d5bebf-4e4`. |
| `workflow-2-result.json` | Full output of `wf_06d5bebf-4e4`: the writer's spec deviations, both review rounds, triage decisions and harness runs. |
| `harness/` | The failure-injection harness: Bukkit and GriefPrevention stubs, `driver/Harness.java`, and `run.sh`, which copies the plugin sources from the working tree, compiles them against the stubs and runs every injection under both inventory semantics. Exits non-zero on any failure. |

## Current state

- `BarterService.java` implements `final_spec.json`. `bash harness/run.sh`
  passes every injection, and `./mvnw -B clean verify` builds.
- Spec entries marked `[amended 2026-09-24]` changed after review round 2:
  the lone-recount case in B9 now refunds removeCurrency's own count (a
  maintainer decision), the PREPARE record names the player by UUID, and the
  S6 and I3 javadoc claims match the bytecode.
- PR #16 must not be merged until a live-server test is done (see
  `CLAUDE.md`, "Build and test").

## Re-running the harness

Needs JDK 25 on `PATH`. `harness/run.sh` finds the repository from its own
location; set `CLAIMBARTER_REPO` to test another checkout. The workflow scripts
still contain absolute paths for the machine they last ran on; edit `REPO`,
`TASKS`, `HARNESS` and `GP_JAR` at the top before running them again.

## Decisions the maintainer still owes

Listed in `judge.json` under `unresolved_disagreements`. The spec defaults to:
rethrow Errors after compensation; keep SEVERE for payout shortfalls; keep one
INFO line per completed trade; report a stack whose drop threw as NOT received;
no synchronous forward completion on the success path.
