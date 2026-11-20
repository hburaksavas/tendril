# Tendril — Roadmap

Tendril adds Spring Boot tooling to IntelliJ IDEA without an Ultimate subscription.
Target: IntelliJ IDEA 2025.3+ (unified distribution). Stack: Kotlin, IntelliJ Platform Gradle Plugin 2.x.

## Done (v0.1)
- [x] Property key + value completion in `application*.properties` / `.yml` (from `spring-configuration-metadata.json`, booleans, enums, hints)
- [x] Unknown-property inspection with a project ignore list (`.idea/…xml`) and Alt+Enter quick fixes
- [x] Endpoint explorer tool window (`@RestController` / `@Controller` mappings, search, jump to code)
- [x] Bean gutter icons (`@Autowired` / `@Inject` fields, constructor parameters → bean definitions)

## Phase 1 — Complete the config story
- [x] Index the project's own `@ConfigurationProperties` classes (and nested types) → completion + no false "unknown" warnings
- [x] Treat keys used in `@Value("${…}")` as known custom properties
- [x] `@Value("${…}")` completion and unresolved-key inspection inside the annotation
- [x] Two-way navigation: config key ⇄ `@Value` / `@ConfigurationProperties` code (gutter icons, Ctrl+Click)
- [x] Profile awareness: profile name completion, profile labels in navigation popups, YAML multi-document (`---`) handling

## Phase 2 — Go deeper on beans
- [x] Reverse navigation: gutter icon on a bean "injected into…"
- [x] Spring Beans tool window (stereotypes, `@Bean`, configuration classes; searchable)
- [x] Inspections: field injection → constructor injection quick fix; no bean found; multiple candidates without `@Primary` / `@Qualifier`
- [x] Circular bean-dependency detection (constructor and field injection, `@Lazy` breaks the cycle)

## Phase 3 — Endpoint explorer
- [x] HTTP-method colours, copy URL / cURL, group by controller, focus mode (only the active editor's endpoints)
- [x] "Go to Endpoint" in Search Everywhere (Endpoints tab)
- [x] Generate a `.http` request (scratch file) for the selected endpoint

## Phase 4 — Running application (Actuator)
- [x] Tool window reading `/actuator/beans`, `/mappings`, `/env`, `/health` of a running app

## Phase 5 — Safety nets & live diagnostics
- [x] Inspection: required `@ConfigurationProperties` property (`@Validated` + `@NotNull`/`@NotBlank`/`@NotEmpty`) missing from every config file
- [x] Actuator: Loggers tab (change log level of the running app, reset to inherited)
- [x] Actuator: Conditions tab (auto-configuration positive/negative matches)

## Phase 6 — Outgoing calls (Feign)
- [x] Feign Clients tool window: resolved address per client for a chosen profile (`url` placeholders, then name-based lookup)
- [x] Name-only clients: address table in settings, well-known config keys, config-key guessing
- [x] Copy URL / cURL / open as `.http` request, gutter icon on Feign methods
- [x] Generate `http-client.env.json` (address per profile) plus a private token file
- [x] Built-in HTTP client ("HTTP Requests" tool window): `.http` syntax, `@var = value` and env-file variables, send and read the response, no paid IDE feature needed
- [ ] Settings page for the service-address table and name key templates

## Quality (alongside every phase)
- [ ] Automated tests (light fixture tests for completion, inspections, markers)
- [ ] Settings page for the ignore list
- [ ] CI build, Marketplace-ready metadata

## Last
- [ ] Spring Initializr project wizard (deliberately postponed)
