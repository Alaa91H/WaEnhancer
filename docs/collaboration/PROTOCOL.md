# Concurrent development protocol

Two engineers work on this repository at the same time, from different machines, and neither
blocks the other. This file is the whole agreement. It is deliberately short: a protocol nobody
reads is worse than no protocol, and every rule below exists because ignoring it produced a
specific failure.

## Lanes and paths

| lane | owns | opens pull requests against |
|---|---|---|
| `mig` | `app/`, `modern-runtime/`, `docs/modernization/`, `docs/architecture/`, `docs/compatibility/` | `main` |
| `aux` | `quality/`, `tools/`, `config/`, `.github/workflows/` (only through an issue it claims), `docs/collaboration/` | `integration/api102-migration` |

A path belongs to exactly one lane. Editing a path you do not own is the one collision the split
cannot resolve on its own, because two machines will resolve it differently.

`integration/api102-migration` is the `mig` lane's alone. The `aux` lane never commits to it and
never force-pushes to any shared branch. That is what makes `main` and the integration branch
contention-free by construction: `aux` work lands in the integration branch first, and `mig`
carries it to `main` in the next pull request.

## Claims and leases

Every unit of work is a GitHub issue, and a task is **claimed the instant the issue carries the
`in-progress` label**.

```bash
gh issue list --state open --label in-progress --json number,title,labels   # before touching anything
gh issue edit NUMBER --add-label in-progress
gh issue comment NUMBER --body "lease: mig | expires <UTC ISO8601, now + 90 minutes>"
```

- If an issue already carries `in-progress`, it belongs to the other lane. Do not implement it, do
  not open a pull request for it, do not comment implementation on it. Take the next one.
- Refresh the lease whenever you finish a step or merge.
- A lease that passes its expiry without a refresh is abandoned. The task returns to the pool
  immediately. Neither side negotiates and **neither side waits**.

## Never stall

If a task needs a path the other lane owns, do not wait and do not stop:

1. open an issue describing exactly the change needed in that path,
2. label it `blocked-on:aux`,
3. record it as deferred in the ledger,
4. move to the next unblocked issue.

The no-idle directive outranks waiting for anyone.

## The shared log

`docs/collaboration/LOG.md` is **append-only**. Exactly one line per claim, merge, release and
handoff:

```
<UTC ISO8601> | <lane> | <issue-or-PR> | <action> | <branch> | <next>
```

Never reorder, reformat or edit an earlier line. If a merge conflict ever touches it, keep both
lines in timestamp order — the history of what happened is the point.

## Merging aux pull requests

`mig` is the single merge authority. Incoming `aux` pull requests are merged into the integration
branch. When one conflicts, resolve it on the integration branch, say so explicitly in the pull
request thread, and preserve the intent of the original change. A conflict is never resolved by
discarding one side.

## What does not change

Every mandatory test still runs on every pull request, and a red check still blocks the merge. No
gate is weakened, bypassed or skipped to get green. No push lands directly on `main`. No branch
holding unmerged work is deleted, and nothing is ever force-pushed to a shared branch. Device
testing belongs to the repository owner, is recorded as `PENDING_USER_DEVICE_TEST`, and never
blocks a merge or a closure.