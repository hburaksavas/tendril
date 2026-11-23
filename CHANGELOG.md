# Changelog

All notable changes to Tendril. The section of the version being built becomes the "What's New" text of the plugin.

## [Unreleased]

### Endpoints
- Addresses resolve without choosing a profile: `spring.profiles.active` of the default config is used, and `spring.profiles.group.*` (comma list or YAML list, nested) pulls in the config files of the group's members
- Incoming endpoints are grouped by controller by default
- Requests open in a real `.http` editor tab (Ctrl+Enter or the gutter arrow); the response shows in the new HTTP Response window, which also holds the environment choice
- A commented `# Authorization: Bearer {{token}}` line no longer asks for a token

### Recordings
- The number of recordings kept per tab is set in the window (was fixed at 10)
- Request body and response are formatted JSON; request headers and response headers have tabs of their own
- Details no longer jump back to the top while the window refreshes; clicking below the last row no longer selects it

### Spring resources
- A `.sql` path in an annotation (`@Value("classpath:db/seed.sql")`, `@Sql`, ...) is a link to the file, and a gutter icon in the `.sql` file lists where it is used

## [0.3.0]

### Recordings
- "Attach the Tendril recorder" checkbox in the Tendril section of every Java-based run configuration (Application, Spring Boot, JUnit, Kotlin, ...), no Maven needed. The agent argument is added when the application starts, not written into the VM options

## [0.2.0]

### Inlay hints (Java and Kotlin, each switchable under Settings | Editor | Inlay Hints)
- `@Value("${...}")` shows the value the key has in the config files of the chosen profile (values of keys that look like secrets are hidden)
- `@FeignClient` shows the address it resolves to
- `@Scheduled` shows the next run of a `cron` expression, or how often a `fixedRate` / `fixedDelay` fires
- `@Transactional` shows propagation, access mode and any isolation, timeout or `rollbackFor`

### Inspections
- `@Transactional` that Spring does not apply: on a private, static or final method or in a final class, a call to a transactional method of the same class (the proxy is bypassed), and a checked exception leaving the method without `rollbackFor` (with a quick fix)

### Recordings
- Timeline tab for incoming requests: the Feign calls made while serving the request, on one time axis, with the time spent in the application itself

### Fixes
- The code-to-config gutter icon no longer logs an error when clicked (uses the platform's target presentation renderer)

## [0.1.0]

First release.

### Configuration
- Completion for properties and values in `application*.yml` and `.properties`, from the Spring Boot metadata of the project's libraries and the project's own `@ConfigurationProperties` / `@Value` keys
- Unknown-key warning that can be ignored per key or per prefix; the ignore list is editable under Settings | Tools | Tendril
- Navigation both ways between yml/properties keys and the Java or Kotlin code that reads them, aware of profiles and of the module that owns the file
- Required `@ConfigurationProperties` values (`@NotBlank` and friends) that no config file defines are flagged before the application fails to start

### Beans
- Bean model with stereotypes, `@Bean` methods, Spring Data repositories and Lombok constructor injection
- Gutter icons from an injection point to its bean and back; inspections for missing, ambiguous and circular beans and for field injection
- Spring Beans tool window

### Endpoints and Feign
- Endpoints tool window (incoming): focus mode, grouping by controller, copy as URL or cURL, open as `.http`
- Feign clients (outgoing) with the address each one resolves to per profile: from the `url` attribute, from well-known config keys or SimpleDiscovery instances, or from a table in the settings
- Ctrl+Click between a `@FeignClient` url placeholder or service name and the config that defines it
- Search Everywhere tab for endpoints

### HTTP client
- `.http` and `.rest` files with highlighting, a run arrow per request and Ctrl+Enter; `@name = value` and `http-client.env.json` variables
- Request section at the bottom of the endpoints window; responses with status, time, formatted body and headers

### Running application
- Actuator tool window: health, beans, mappings, environment, loggers (change a level) and conditions
- Recordings: a bundled agent records incoming requests, Feign calls and Rabbit/Kafka listener input of an application started with `spring-boot:run`; replay requests, republish messages
- "Add Recorder to Maven Run Configuration"

### Kotlin
- Everything above works from Kotlin sources: inspections, gutter icons, references and completion run on UAST
