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
| `workflow-2-implement-verify.js` | The workflow that implements the spec, builds the harness, runs it to green, and reviews until dry. Run id `wf_8697fa39-eb6`. **Stopped before any agent wrote to disk**; resume it to continue. |

## State when this was committed

- Branch `fix/purchase-rollback` at the commit before this one holds the fifth
  review round's code. It compiles. It is NOT the spec implementation and it is
  NOT mergeable: the fifth review found real defects (see `judge.json`
  "REVIEW_HISTORY" items 1-17 in `workflow-1-design.js`).
- `dev` has PRs #14, #15, #17, #18 merged. PR #16 is open and must not be
  merged until the harness passes and a live-server test is done.

## How to resume

The scripts contain absolute paths for one machine (`/Users/diegotalamantes/...`,
`/private/tmp/claude-505/...`). On another machine, edit `REPO`, `TASKS`,
`HARNESS` and `GP_JAR` at the top of `workflow-2-implement-verify.js`, point
`SPEC` and `PLAN` at the copies in this directory, then run it with the Workflow
tool. Completed-agent caches do not travel; expect a full run.

## Decisions the maintainer still owes

Listed in `judge.json` under `unresolved_disagreements`. The spec defaults to:
rethrow Errors after compensation; keep SEVERE for payout shortfalls; keep one
INFO line per completed trade; report a stack whose drop threw as NOT received;
no synchronous forward completion on the success path.
