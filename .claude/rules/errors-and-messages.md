---
description: Use when throwing an API error, adding an error code, or editing messages/errors.properties or errors_sv.properties.
paths:
  - "src/main/resources/messages/**"
  - "src/test/java/com/parkview/ruleengine/web/**"
last-verified: 2026-11-19
---

# Errors and messages

* There is no handler in this repo: problem+json comes from the platform `ApiExceptionHandler`. Throw the platform exceptions: `ResourceNotFoundException(resource, id)` (404, code `notFound.<resource>` with spaces of the name as `-`, e.g. `notFound.dispute-of-fine`), `ConflictException.coded(...)` (409), `BusinessRuleException.coded(code, english, args...)` (422, e.g. `zone.otherSite`, `permit.plateEmpty`). Never catch and return null.
* The `code` is stable (clients may switch on it) and written `<area>.<reason>` in lower camel case. Its text is the key of the same name in `messages/errors.properties` (English) and `messages/errors_sv.properties` (Swedish, plain UTF-8, no `\u` escapes).
* Both bundles hold the same keys (`ErrorMessagesBundleTest`); `ApiExceptionHandlerLocaleTest` checks the language selection. Avoid apostrophes in texts: a text without parameters is used as is, one with parameters goes through `MessageFormat` where a literal apostrophe is `''`.
* The bundle is chained under the platform bundle `parkview-errors*.properties`: do not repeat a platform key unless this service words it differently.
* No stack trace, SQL or upstream body in a response. Dispute message and decision note are personal text: shown to the driver and staff, never logged, never in an event. No plate unmasked at INFO or above.

## When it does NOT apply

Successful responses, DTO shape, and 401/403 bodies (platform security chain and platform bundle).
