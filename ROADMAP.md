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
- [ ] Reverse navigation: gutter icon on a bean "injected into…"
- [ ] Spring Beans tool window (stereotypes, `@Bean`, configuration classes; searchable)
- [ ] Inspections: field injection → constructor injection quick fix; no bean found; multiple candidates without `@Primary` / `@Qualifier`
- [ ] Circular constructor-dependency detection

## Phase 3 — Endpoint explorer
- [ ] HTTP-method colours, copy URL, group by controller
- [ ] "Go to Endpoint" in Search Everywhere
- [ ] Generate a `.http` request file for the selected endpoint

## Phase 4 — Running application (Actuator)
- [ ] Tool window reading `/actuator/beans`, `/mappings`, `/env`, `/health` of a running app

## Quality (alongside every phase)
- [ ] Automated tests (light fixture tests for completion, inspections, markers)
- [ ] Settings page for the ignore list
- [ ] CI build, Marketplace-ready metadata

## Last
- [ ] Spring Initializr project wizard (deliberately postponed)
