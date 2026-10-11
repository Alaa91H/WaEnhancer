# Shared log

Append-only. One line per claim, merge, release and handoff.
Format: `<UTC ISO8601> | <lane> | <issue-or-PR> | <action> | <branch> | <next>`

2026-10-11T00:31:56Z | mig | #479 | merged fix(p0-450-target-privacy-rules) into main | main | ff-only resync of integration/api102-migration
2026-10-11T00:40:54Z | mig | #480 | merged fix(#391) evidence identity into main | main | ff-only resync of integration/api102-migration
2026-10-11T00:45:00Z | mig | #450 | closed with merged evidence 4c7bff32 | integration/api102-migration | claim next unstarted queue item
2026-10-11T01:05:00Z | mig | #483 | merged fix(#394) preference-key inventory into main | main | ff-only resync; close #394
2026-10-11T01:10:00Z | mig | #10 | claim lease: mig | in-progress label applied | integration/api102-migration | truthful catalog metadata, then preparation, then editor
2026-10-11T01:12:00Z | mig | #485 | opened blocked-on:aux for the resolver-name gate in tools/ | integration/api102-migration | continue #10 without waiting on tools/
2026-10-11T01:20:00Z | mig | #448 | closed: all four P0-CORE children merged and verified | integration/api102-migration | close #357, #451, #452 with evidence
2026-10-11T01:26:00Z | mig | #357 | closed with merged evidence 5c8d91b6 | integration/api102-migration | close #451 and #452
2026-10-11T01:28:00Z | mig | #451 | closed with merged evidence 3b6b4842 | integration/api102-migration | close #452
2026-10-11T01:29:00Z | mig | #452 | closed with merged evidence 9a9d0a72 | integration/api102-migration | return to #10
2026-10-11T01:40:00Z | mig | #394 | closed with merged evidence b3df7cdc | main | next unit of #10
2026-10-11T01:55:00Z | mig | #486 | opened fix(#10): truthful catalog + preparation + editor | integration/api102-migration | fix CI red at the cause, merge on green
2026-10-11T02:05:00Z | mig | #10 | fixed mux flags bug and unmocked-log defect found by CI | integration/api102-migration | merge when the exact commit is green
2026-10-11T02:06:00Z | mig | #468 | merged aux pull request (QUEUE.md) into the integration branch | integration/api102-migration | create LOG.md and PROTOCOL.md, then continue #10
