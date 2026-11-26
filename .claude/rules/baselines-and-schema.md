---
description: Use when changing the Liquibase baseline, the published views v_fines and v_fine_evidence, a JPA entity or repository, a read model or jpa directory of another service's view, the retention SQL, or the test schema script.
paths:
  - "src/main/resources/db/changelog/**"
  - "src/main/java/com/parkview/ruleengine/domain/**"
  - "src/main/java/com/parkview/ruleengine/repository/**"
  - "src/main/java/com/parkview/ruleengine/readmodel/**"
  - "src/main/java/com/parkview/ruleengine/client/**"
  - "src/main/java/com/parkview/ruleengine/service/retention/**"
  - "src/test/resources/test-schema.sql"
  - "src/test/java/com/parkview/ruleengine/persistence/**"
last-verified: 2026-11-19
---

# Baseline, views and read models

The schema `rules` and the role `svc_rule` (and the grants to the Supabase roles) are created by the infrastructure, never by this repo (ADR 0010). Names are unqualified.

## Baseline

* One file, `db/changelog/001-baseline.sql`: changesets `1-baseline`, `2-baseline-evidence-guard` and `3-published-views` (`runOnChange:true`).
* While nothing is released the baseline is edited in place (ADR 0010). After the first release a change is a new numbered changeset and a PR touching the baseline needs a breaking-change review.
* A baseline is the final schema: no fill triggers, no reconcile logic, no `if exists` migration guards, no demo seeds. Inside the schema foreign keys restrict: nothing is deleted by a cascade, only by the retention job. No foreign key leaves the schema; ids of spots, zones, sites, cameras and users are plain columns, and mapping deletions are handled by reacting to `map.updated`.
* Rules in the database, not only in code: `spot_violations_open_uq`, `spot_violations_fine_uq`, `fine_disputes_one_open_uq`, and the trigger `fine_evidence_immutable` (the frozen evidence, CLAUDE.md §3; the retention transaction may replace `plate`, `image_paths`, `detection_ids` only).
* Money columns follow ADR 0014: amount plus `currency` (`^[A-Z]{3}$`).

## Foreign data

* Other services' data is read only through `@Immutable` read models with `@Table(schema, name)` over the published views of mapping, sessions, identity and ingestion (listed in CLAUDE.md §1). They map a subset of the view, never write, and Liquibase never touches them.
* Each read sits behind a port in `client/` (`ZoneDirectory`, `SpotDirectory`, `ActiveSessionDirectory`, ...) with the JPA implementation in `client/jpa`, so it can become an API call without touching business code.
* `src/test/resources/test-schema.sql` recreates those foreign schemas and views as the owners publish them. When an owner changes a view, change the script in the same step.

## Published views

`v_fines` and `v_fine_evidence` are read by ingestion as role `svc_ingestion` (grants guarded by role existence). A new reader column means a change of the view first. Never grant a base table.

## Retention SQL

`service/retention/RuleRetention` runs the clean-up of evidence references, closed records and unfined violations. It is off and dry-run by default and refuses to start enabled without the three period variables (`PARKVIEW_RETENTION_EVIDENCE`, `PARKVIEW_RETENTION_CLOSED_RECORDS`, `PARKVIEW_RETENTION_VIOLATIONS`). Keep every step bounded (`RetentionBatches`) and idempotent; counts and table names in logs, never plates.

## When it does NOT apply

Rule evaluation, controllers and listeners that do not change a table, view or read model. Event payloads: `events-and-outbox.md`.
