---
description: Use when adding or changing an endpoint, a @PreAuthorize rule, the generated Permission catalogue, RuleAccessPolicy or FineAccess, or how sites and plates of the caller are resolved.
paths:
  - "src/main/java/com/parkview/ruleengine/controller/**"
  - "src/main/java/com/parkview/ruleengine/security/**"
  - "src/test/java/com/parkview/ruleengine/controller/**"
  - "src/test/java/com/parkview/ruleengine/security/**"
last-verified: 2026-11-19
---

# Security and permissions

## Catalogue

The generated block of `Permission.java` is delimited by `BEGIN GENERATED permissions` / `END GENERATED permissions`; `generate_permissions.py --check` verifies it and the result is committed in every repo it touched (how to regenerate: CLAUDE.md §3). Read and write are separate permissions (ADR 0015).

## Two layers

1. Role or permission on the method with `@PreAuthorize`: `violation:read` (violations, fines, evidence, explanation), `violation:resolve`, `fine:read` / `fine:manage` (disputes and their decisions, void, mark paid), `permit:read` / `permit:manage`. Driver access is written `hasRole('DRIVER') or hasAuthority(...)`. This service has no public path (`public-paths: []`).
2. Object check: `RuleAccessPolicy` (subclass of the platform `AccessPolicy`, and the `AccessPolicy` bean: the platform default backs off) and `controller/FineAccess`. An operator reaches a zone through its site (`siteOfZone`), a list of a plate is limited to their sites, a driver reaches only their plates. A fine the caller may not see is reported as unknown (404), so the answer never reveals it exists. Decide against the claims and the directories, never against request parameters.

## Sites and plates of the caller

* The token holds `sub`, `role`, `permissions` only (ADR 0011). `ReadModelOperatorSites` (over `identity.v_user_sites`) and `ReadModelPlateDirectory` (over `identity.v_user_plates`) implement the platform ports and are cached (`parkview.access.*-cache-ttl`, 30 s). A database error refuses the request.
* `GET /internal/v1/fines/{id}` is opened by the platform filter chain with this service's own key (`ROLE_INTERNAL`) and has no `@PreAuthorize`. `lookup-service` and `file-service` call the public endpoint with the caller's token instead (its contract, ADR 0012: CLAUDE.md §3).
* The call to lookup-service uses its own key `LOOKUP_INTERNAL_API_KEY` (no fallback to `INTERNAL_API_KEY`), explicit timeouts, and `UpstreamCall`; the caller's token is never logged.

## New endpoint checklist

A row in the gateway matrix (`parkview-gateway`, `application.yml`, keys `read:`/`write:`) and an e2e case. A web-slice test (401, 403 wrong permission, 403 or 404 wrong site or plate, 200, problem+json shape) next to the existing controller tests, with the local `controller/PlatformWebSlice` and `WebTestSupport`.

## When it does NOT apply

Rule evaluation, listeners and persistence. Table changes: `baselines-and-schema.md`.
