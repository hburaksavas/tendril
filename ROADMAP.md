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
- [x] SQL tab in incoming HTTP recording details: opt-in capture of synchronous JDBC prepared statements, with execution count and total duration per template. Replay the request to check counts and timing after a change.

## Kotlin support (measured by KotlinSupportProbe, see build/kotlin-support.txt)
- [x] Works from Kotlin sources: endpoint scanner and request text, Feign scanner and address resolver, @ConfigurationProperties (data classes, shorthand), @Value on constructor parameters, bean model (stereotypes, @Bean functions, constructor and lateinit field injection), yml to Kotlin navigation
- [x] Inspections (bean, required property, unresolved key), bean/config/Feign gutter icons, Ctrl+Click from a placeholder or a Feign service name, and placeholder completion run on UAST / Kotlin PSI, so they work in Kotlin files too (KotlinSupportProbe asserts all 25 checks)
- [ ] Kotlin quick fixes (annotate as @Component, convert to constructor injection) are only offered in Java files. Feasible; skipped for now

## Quality (alongside every phase)
- [x] Automated tests: unit tests, light fixture tests (scanners, Feign resolver, navigation, inspections) and recorder tests (121 so far; completion and gutter markers are not covered yet)
- [x] Settings page for the ignore list (see Phase 6)
- [x] CI build (build, release, verify workflows), Marketplace-ready metadata

## Phase 8 — Recordings, deeper
- [ ] N+1 warning: a statement template run many times in one request gets a badge in the SQL tab, with a link to the repository method that issued it
- [ ] Compare two recordings of the same endpoint side by side: response body, duration, SQL counts, Feign calls (before/after a change)
- [ ] Generate a `MockMvc` / `WebTestClient` test from a recording (Feign calls as mocks, recorded response as assertions)
- [ ] Export and import recordings (`.http` or HAR) to share them and replay them elsewhere
- [ ] Fault injection for outgoing calls: from a Feign recording, make the next calls of that client return a chosen status or wait a chosen time (through the recorder agent), to exercise retry, circuit breaker and fallback code without touching the other service
- [ ] Endpoints with no test: an Endpoints filter for mappings whose URL appears in no `MockMvc` / `WebTestClient` / `@WebMvcTest` test
- [ ] Across services: when both sides run the recorder, the agent adds a header to outgoing calls; a Feign recording opens the incoming recording it caused in the other service, giving one timeline over several services
- [ ] Logs tab: the log lines written while serving a request (matched by MDC or trace id)
- [ ] Transaction boundaries on the timeline: which method opened a transaction, commit or rollback, and which SQL ran inside it
- (Feign recordings served as stubs: see Phase 7)

## Phase 9 — Configuration, deeper
- [ ] Config Explorer window: the effective value of every key for a profile and the file it comes from (`application.yml`, `application-dev.yml`, a profile-group member)
- [ ] Inspection: a key in a config file that no code reads and no metadata knows (the reverse of the unknown-key warning)
- [ ] `@Value` inlay hint mentions the environment variable that would override the key (`SERVER_PORT`)
- [ ] Deprecated keys from the metadata `deprecation` entries, with a quick fix to the replacement key (Boot 2 → 3 migration)
- [ ] Inspection: a password, secret or token written as plain text in a config file, with a quick fix to `${ENV_VAR}`
- [ ] One profile chooser in the status bar, used by inlays, addresses, conditions and the Config Explorer (instead of a chooser per window)
- [ ] Environment variables in Kubernetes manifests, Helm values and `docker-compose.yml` (`SPRING_DATASOURCE_URL`): Ctrl+Click to the key, warning for a name that matches no key
- [ ] `spring.config.import` (`optional:file:`, `configtree:`, `classpath:`): imported files join the config model
- [ ] Quick fix "Document this key": adds a custom `@Value` key with description and default to `additional-spring-configuration-metadata.json`

## Phase 10 — Beans and code analysis
- [ ] Dependency graph from a controller through services and repositories to Feign clients (replaces the switched-off Spring Beans window; joins the service-method tree of Phase 7)
- [ ] Self-invocation inspection for `@Async` and `@Cacheable` (as for `@Transactional`)
- [ ] Inspections: `@Scheduled` without `@EnableScheduling`, `@ConfigurationProperties` class that is neither enabled nor scanned
- [ ] Conditions and profiles: an inlay on `@ConditionalOnProperty`, `@Profile` and `@ConditionalOnMissingBean` saying whether the bean is active for the chosen profile and why (`feature.x.enabled=false`); inactive beans are greyed out
- [ ] Spring Modulith: inspection for dependencies that cross module boundaries
- [ ] Spring events: gutter navigation both ways between `publishEvent(...)` and `@EventListener` / `@TransactionalEventListener` by event type; inspection for an event type no listener receives
- [ ] Inspections: `@RequestBody` whose class has constraint annotations but no `@Valid` / `@Validated`; `@Async` method returning something other than `void` or a `Future`; private `@EventListener` method
- (Kotlin quick fixes: see Kotlin support)

## Phase 11 — Messaging and contracts
- [ ] Messaging window: `@KafkaListener` / `@RabbitListener` (incoming) and `KafkaTemplate` / `RabbitTemplate` sends (outgoing), topic and queue names resolved from config
- [ ] OpenAPI: generate `openapi.yaml` from the endpoints, or report differences between an existing spec and the code
- [ ] Actuator: metrics and `httpexchanges` tab with simple charts
- [ ] Outgoing HTTP beyond Feign: `RestClient`, `WebClient`, `RestTemplate` and `@HttpExchange` interfaces in the Outgoing tab, with addresses resolved per profile
- [ ] Sample JSON body in generated `.http` requests, built from the request DTO (`@JsonProperty`, `@JsonIgnore` respected)
- [ ] Error mapping per endpoint: which exception becomes which status, from `@ControllerAdvice` / `@ExceptionHandler`

## Phase 12 — Spring Data JPA
- [x] Entity model: `@Entity` / `@Embeddable` / `@MappedSuperclass` (jakarta and javax), inherited attributes, field or property access, `@Transient`, collection element types, repository domain type (Java and Kotlin)
- [x] `@Query` (JPQL): unknown entity and attribute inspection, also through joins, embeddables and `#{#entityName}`; Hibernate's `id`, `size`, implicit subtype attributes are accepted
- [x] `@Query`: completion of entity names, attributes after `alias.`, aliases and `:parameter` names
- [x] `@Query`: Ctrl+Click to the entity, attribute and method parameter; rename of an entity, attribute or parameter updates the query
- [x] Parameter binding (JPQL and native): `:name` / `?n` with no method parameter, unused method parameters, named and positional mixed; `Pageable`, `Sort` and SpEL are understood
- [ ] Derived query methods (`findByEmailAndStatus`): attribute completion and validation, generated JPQL as an inlay
- [ ] Return type against the `SELECT` clause; `@Modifying` missing on `UPDATE` / `DELETE`; plural attribute dereferenced without a join
- [ ] `@NamedQuery` and `orm.xml`; constructor expressions (`new com.x.Dto(...)`) resolved to the class and constructor
- [ ] Native SQL against `@Table` / `@Column` and Flyway / Liquibase migrations
- [ ] Inspection: two Flyway migrations with the same version (common after a merge) or out-of-order versions
- [ ] Recordings: from a SQL template to the repository method that ran it; N+1 → `JOIN FETCH` / `@EntityGraph` quick fix

## Phase 13 — Startup diagnostics
- [ ] Startup failure analyzer: the "APPLICATION FAILED TO START" report in the Run console links to the bean, config key or port it is about, with a quick fix where one exists (missing bean, property that does not bind, port in use)
- [ ] Startup timeline from Actuator `/startup`: time spent initialising each bean, on the Recordings timeline view

## Phase 14 — Security and SpEL
- [ ] Endpoints show the access rule that applies to each mapping (`requestMatchers(...).hasRole(...)` / `permitAll()` of the `SecurityFilterChain`, method `@PreAuthorize`), and flag mappings no rule covers
- [ ] SpEL in `@PreAuthorize`, `@Cacheable(key = ...)`, `@Value("#{...}")` and `@ConditionalOnExpression`: completion of method parameters and bean names, unknown parameter inspection (reuses the `@Query` parameter binding)

## Phase 15 — Running application, deeper
- [ ] Scheduled Tasks window: every `@Scheduled` method with its next run and Actuator `/scheduledtasks` data; "Run now" through the recorder agent
- [ ] Pending migrations: Actuator `/flyway` / `/liquibase` compared with the migration files of the project; files not yet applied get a gutter icon
- [ ] Actuator Caches tab: cache names, evict with one click, navigation to `@Cacheable` usages

## Last
- [ ] Spring Initializr project wizard (feasible; deliberately postponed, skipped for now)
