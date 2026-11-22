# CLAUDE.md - parkview-rule-service

Artifact `ruleengine`, Java package `com.parkview.ruleengine`. `AGENTS.md` is a thin pointer to this file. Hub and standards: `../parkview-e2e/CLAUDE.md`; shared library: `../parkview-platform/CLAUDE.md`. Not repeated here.

## §1 Project and stack

Decides whether a parked vehicle breaks a parking rule and turns breaches into violations, fines and disputes. It is the only place that decides whether a vehicle stands correctly in its spot.

* Port `8081`. Java 21, Spring Boot 3.5.3 (from `parkview-parent`), JPA + Liquibase, RabbitMQ, Caffeine, Lombok, JTS (`geometry/`), Jollyday (holidays). Dependencies: `parkview-service-starter`, `parkview-outbox`, `parkview-domain`.
* Owns schema `rules`, login role `svc_rule`: `spot_violations`, `fines`, `plate_permits`, `fine_actions`, `fine_evidence`, `fine_disputes`, `fine_public_lookups`, `outbox_events`. Publishes views `v_fines` and `v_fine_evidence` (to `service_role`, the PostgREST role of ingestion). Reads `mapping.v_sites/v_zones/v_zone_rules/v_parking_spots`, `sessions.v_sessions`, `identity.v_user_plates/v_user_sites`, `ingestion.v_detections`. Calls lookup-service for vehicle data (`LOOKUP_INTERNAL_API_KEY`).
* Publishes (outbox, exchange `parkview.violation`): `violation.created`, `violation.resolved`, `fine.issued`, `fine.paid`, `fine.voided`, `fine.disputed`, `fine.dispute.decided`. The relay's transport is `parkview.outbox.transport` (`amqp` by default, `both` in the e2e stack: the same rows go to RabbitMQ and to the Kafka topic of the exchange; `parkview-outbox`, ADR 0021 of parkview-e2e).
* Consumes: `vehicle.detected` (ingestion), `session.ended` (session), `payment.completed.fine` (payment), `map.updated` (mapping, per-instance queue). Internal API: `GET /internal/v1/fines/{id}`.

```
engine/             rule evaluation: RuleResolver, ViolationEvaluator, RuleSchedule, Observation, Finding
geometry/           BoundingBox, Polygon, PolygonGeometry (JTS)
service/violation/  DetectionService, BoundaryService, ViolationOpener/Resolver, PermitService, ViolationJobs
service/fine/       issuing, settlement, management, evidence, disputes, FineEvents
service/cache/ service/retention/   rule and spot caches; nightly retention (advisory lock)
controller/         violations, fines, evidence, disputes, permits; internal/ (fine lookup)
messaging/          listeners (vehicle, session, payment, map), AmqpSupportConfig, DeadLetterConfig, ListenerRetryConfig
client/ readmodel/  ports (ZoneDirectory, SpotDirectory, ...), client/jpa implementations, client/http vehicle lookup, @Immutable views
domain/ repository/ entities (Violation, Fine, Dispute, FineEvidence, PlatePermit, FineAction), repositories
security/           generated Permission, RuleAccessPolicy, ReadModelOperatorSites, ReadModelPlateDirectory
config/ dto/ mapper/   RuleEngineProperties, RabbitConfig (topology, keys); dto/event holds every event record
```

## §2 Build and test

Run from the repo root with JDK 21 (`C:\Users\yasin.koyuncu\.jdks\azul-21.0.8`). CI (`.github/workflows/ci.yml`) runs `mvn -B -ntp verify` and then a Docker build.

```bash
mvn -B -ntp verify                              # compile, unit and web-slice tests, Testcontainers tests, jar
mvn -B -ntp -o -q -DskipTests test-compile      # compile-only check (-o: parkview-platform comes from ~/.m2; -llr has worked when Maven refuses it)
```

* Add `-s <settings file without the corporate mirror>`: the default `~/.m2/settings.xml` mirrors everything to the Nexus, which is unreachable. Never plain `mvn` (the default JDK is 8).
* Docker needed (Testcontainers `postgres:16` with `src/test/resources/test-schema.sql`, `rabbitmq:3.13-management`): `persistence/*`, `readmodel/ReadModelsTest`, `client/jpa/*`, `RuleServiceFlowTest`, `messaging/MapUpdatedBrokerTest`, `service/fine/FineIssuingTransactionTest`. They are not guarded by `disabledWithoutDocker`: without Docker they fail rather than skip. In an agent session they are not run: say "compiled only", never "works".
* Web slices use the local `src/test/java/com/parkview/ruleengine/controller/PlatformWebSlice.java`. `ErrorMessagesBundleTest` guards the two bundles.
* Image: `docker build --secret id=maven_settings,src=<settings.xml with server id github> .`

## §3 Invariants

1. The generated block in `security/Permission.java` is never edited by hand: change `../parkview-e2e/contracts/permissions.json`, run `python contracts/generate_permissions.py` there.
2. The thresholds (`iou-threshold`, `containment-threshold`, `min-overlap`, the grace periods) exist only in `RuleEngineProperties` / `application.yml`. Time of an observation is the event's `timestamp`, not "now"; inject `Clock`. Spots of a camera view (`image_polygon`) and legacy geo spots are never mixed in one match.
3. Fine evidence is frozen when the fine is issued; a database trigger rejects every delete and every update except the retention transaction (`parkview.retention=on`). At most one dispute per fine is open (unique index) and an open dispute blocks payment. Dispute text is never logged and never in an event.
4. One open violation per plate, spot and type and one violation per fine are unique indexes, not only code. Catch `DataIntegrityViolationException` as "already exists".
5. Events only through `parkview-outbox` in the transaction of the change; never `RabbitTemplate` directly. Redelivery must not duplicate: state check or unique index. A payment that cannot be accounted for is recorded for review, never dropped (`.claude/rules/events-and-outbox.md`).
6. `GET /api/v1/fines/{id}` is a contract for lookup-service and file-service (ADR 0012): 200 visible, 404 unknown or not visible. `GET /internal/v1/fines/{id}` carries `voided` and `disputed` for payment-service.
7. Other services' data only through `v_*` read models behind the ports in `client/`; published views, read models and baseline policy (ADR 0010): `.claude/rules/baselines-and-schema.md`.
8. Money is `Money` (amount + ISO 4217 currency), no default currency; sites and zones go through `Place`; plates through `Plates` and `@PlateNumber`, never unmasked in logs at INFO or above.
9. The one public handler is `PublicFineController` (ADR 0020: fine number + plate, no token, no `@PreAuthorize` on purpose; path in `parkview.security.public-paths` and PUBLIC in the gateway matrix). It answers `EvidenceMapper.toPublic` only, the same 404 for an unknown number and a wrong plate, a per-number lock, and it never stores the plate. Any other new handler needs `@PreAuthorize`.
10. Security, web, error handling and outbox plumbing come from `parkview-platform`: do not copy them back. A new endpoint needs a gateway matrix row (`../parkview-gateway`, `application.yml`) and an e2e case.

## §4 Process

* Branches: `master` = release, `develop` = development (no `main`). Conventional commits (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`), the body says why.
* Commits are made by the user and GPG-signed. **Claude does not commit or push on its own.** Stage files explicitly, never `git add -A`; never commit `.env`, `.idea` or `.claude/settings.local.json`.
* Build facts: JDK 21 only; the corporate mirror is unreachable, so use a settings file that skips it; `mvn clean` can fail on a locked `target` on Windows: delete `target` by hand.
* After a baseline change the e2e database volume must be rebuilt (`down -v`).

## §5 Pointers

* Standards: `../parkview-e2e/docs/platform-standards.md` (2 security, 4 messaging and 4.2 outbox, 6 persistence and 6.0 who decides, 8.2 retention, 9 testing); event schemas `../parkview-e2e/contracts/events.json`. ADRs in `../parkview-e2e/docs/adr/`: 0001, 0005 (disputes), 0009, 0010, 0011, 0012, 0013, 0014, 0019.
* Review: `../parkview-e2e/docs/reviews/review-rule.md`. Library: `../parkview-platform/docs/consuming.md`. README: rule engine, flow of one observation, retention.
* Rules (`.claude/rules/`): `baselines-and-schema.md`, `events-and-outbox.md`, `security-and-permissions.md`, `errors-and-messages.md`.
