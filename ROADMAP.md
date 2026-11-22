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
- [x] Settings page (Settings | Tools | Tendril): ignored keys, service-address table, extra address-key templates, recorder port and token

## Phase 7 — Recordings (record and replay at runtime)
- [x] Recorder agent (`recorder` module, bundled in the plugin): a `-javaagent` that Spring Boot picks up as an auto-configuration; works with `spring-boot:run`
- [x] Incoming HTTP with bodies, handler and flow; outgoing Feign calls with responses; `@RabbitListener` / `@KafkaListener` input
- [x] Recordings tool window (own icon): latest 10 per kind, details, replay (HTTP, Feign) and republish (messages, through the application's own template)
- [x] "Add Recorder to Maven Run Configuration" (toggles the agent in `spring-boot.run.jvmArguments`), plus "Copy Agent Argument" for other run types
- [x] Secrets masked before leaving the application (auth headers, password/token fields); loopback + token only
- [ ] Spring Boot 2 / javax servlet, Gradle `bootRun` helper, `.jar` launch (fat jar class loader)
- [ ] Service-method tree (calls between beans) and recording of non-HTTP outgoing calls
- [ ] Feign recordings served as stubs

## Kotlin support (measured by KotlinSupportProbe, see build/kotlin-support.txt)
- [x] Works from Kotlin sources: endpoint scanner and request text, Feign scanner and address resolver, @ConfigurationProperties (data classes, shorthand), @Value on constructor parameters, bean model (stereotypes, @Bean functions, constructor and lateinit field injection), yml to Kotlin navigation
- [x] Inspections (bean, required property, unresolved key), bean/config/Feign gutter icons, Ctrl+Click from a placeholder or a Feign service name, and placeholder completion run on UAST / Kotlin PSI, so they work in Kotlin files too (KotlinSupportProbe asserts all 25 checks)
- [ ] Kotlin quick fixes (annotate as @Component, convert to constructor injection) are only offered in Java files

## Quality (alongside every phase)
- [x] Automated tests: unit tests, light fixture tests (scanners, Feign resolver, navigation, inspections) and recorder tests (121 so far; completion and gutter markers are not covered yet)
- [x] Settings page for the ignore list (see Phase 6)
- [ ] CI build, Marketplace-ready metadata

## Last
- [ ] Spring Initializr project wizard (deliberately postponed)
