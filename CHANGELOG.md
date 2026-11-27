# Changelog

All notable changes to Tendril. The section of the version being built becomes the "What's New" text of the plugin.

## [Unreleased]

## [0.9.1]

### Fixes
- Profile groups (`spring.profiles.group.production: proddb, prodmq`) and their members are offered as profiles even without an `application-production.yml`: in the Feign Clients profile chooser and in `spring.profiles.active` completion. Choosing a group already applied its members

## [0.9.0]

### Mocked responses
- Right-click | Mock Response inside a Feign client method or a controller handler: a mock file under `.tendril/mocks` with a sample body built from the return type (DTOs, records, Kotlin data classes, collections, `ResponseEntity`, `Optional`, `Mono`), opened for editing
- While the application runs with the recorder, a Feign call that matches a mock is answered by it without reaching the service (matched by client method, whatever the address, Eureka or load balancer), and a request that matches an endpoint mock is answered without reaching the controller
- A mock can narrow what it answers (the end of the path, query parameters, headers, and for Feign a text the body contains), wait before answering (`delayMs`), and for Feign fail like the network would (`fault`: `timeout` or `refused`) to exercise retries, circuit breakers and fallbacks
- "Mock This Response" on a recorded Feign call or request turns what the service or controller returned into a mock
- Mocks tab in the Recordings window: every mock with a switch, all mocks on or off at once, and whether the application holds them. Edits apply when the file is saved, and a restarted application gets the mocks again on its own
- Recordings answered by a mock are marked "mocked"

### Feign clients from the backend
- Right-click | Create Feign Client Method… inside a controller handler: the Feign method that calls it is written into an open project (the BFF), or into this one, in the client that already calls this service (found by name or by a local address on the backend's port) or in a new one
- The method follows the endpoint: path (below the client's `path`, context path included), path variables, query parameters (optional ones nullable), headers, body, an object of query parameters as `@SpringQueryMap`, `Pageable`; `ResponseEntity`, `Optional`, `Mono` and futures are unwrapped, `Flux` becomes a list. Server-side parameters (request, principal, model) are left out
- The backend classes the endpoint uses are copied as DTOs: records (classes before Java 16) or Kotlin data classes with their JSON names, enums with their constants, superclass fields included; a class of that name already in the project is used instead
- A new client gets `name` from `spring.application.name`, `path` from the context path, and, when the project's clients give addresses, `url = "${service.url:http://localhost:port}"`. A method that calls the endpoint already is pointed out
- Java or Kotlin, as the client or the project is written; imports are added and the code is formatted

### Test messages
- Right-click | Send Test Message inside a `@KafkaListener` or `@RabbitListener` method (or a `@KafkaHandler` / `@RabbitHandler` of a class-level listener): a dialog with the topic or queue read from the annotation (placeholders resolved from the config, `@QueueBinding` exchange and key) and a sample payload built from the parameter type, past `@Header`, `Acknowledgment`, `Channel`, and inside `Message<T>` / `ConsumerRecord<K, V>`
- The message is sent through the running application with its own connection settings (SASL, SSL), so nothing is configured in the IDE. Kafka records go out as the bytes written, not encoded again by a `JsonSerializer`, with the `__TypeId__` header a `JsonDeserializer` expects (optional)
- Batch listeners take a JSON array, one message per element; the last payload, key and headers are kept per listener. What the listener did shows in Recordings | Messages

### Tests from recordings
- "Generate Test" on a recorded request writes a `@SpringBootTest` + `MockMvc` test into the controller module's test folder, in the controller's language (Java or Kotlin): the recorded request is sent again (method, path and query, context path, headers, body) and the recorded status and body are expected (`content().json(...)`, lenient)
- The Feign calls made while serving the request become `@MockitoBean` stubs (`@MockBean` before Boot 3.4) returning what they returned, read into the method's return type with the application's `ObjectMapper` (`Optional` and `ResponseEntity` included); several calls of one method return in order
- What the test cannot do by itself is written as a comment: headers the recorder masked, Feign calls that failed (to stub with `willThrow`), secret fields masked in the response

### Requests
- Generated `.http` requests and cURL commands get a fuller sample body from the request DTO: `@JsonProperty` names, `@JsonIgnore` and `transient` fields left out, records and Kotlin data classes, generics, one sample element per list, superclass fields
- A Feign method's body is found without `@RequestBody` too, as Feign does; cURL puts the body on one line
- A Feign `@SpringQueryMap` / `@QueryMap` parameter fills the query of the generated request with its fields (superclass fields, `@feign.Param` names, sample values); a `Map` becomes `{{query}}`

## [0.8.0]

### Service calls across projects
- Link the other projects of a system (backend, BFF, React frontend) under Settings | Tools | Tendril, picked from the recently opened projects or as a folder
- Right-click | Find Service Calls inside a controller handler or a Feign method shows the chain across them: which `fetch` calls of the frontend, which Feign calls of the BFF and the endpoints that make them call it, and what it calls in turn; double-click opens the code, in the window of its project when that is open
- A BFF endpoint knows the Feign calls it makes through the services, interfaces and private methods it calls (Java and Kotlin)
- Feign calls find their service by address (local port, or a host named like `spring.application.name`) or by client name, then the endpoint by method and path including the context path; a call no linked application serves is matched by path only, and says so
- `fetch` calls are read from the frontend's files, with base addresses from `.env` (`REACT_APP_*`, `VITE_*`) or a constant of the file, and the method from the options
- A linked project open in another window is read live; a closed one from what Tendril saved in the background the last time it was open (`~/.tendril/service-map`)

## [0.7.2]

### Performance
- Typing no longer rebuilds the project-wide models (beans, config keys and the code that reads them, endpoints, Feign clients, JPA entities): they are rebuilt only when declarations change (classes, members, annotations, signatures), not on edits inside method, constructor or initializer bodies. The JPA model was rebuilt on any edit in any file, config files included
- Config files and profiles are found by file type instead of by going through every file name the IDE has indexed (all open projects, libraries and the JDK); the profile list is no longer rebuilt on code edits
- The profile a YAML document belongs to is computed once per change of its file, not on every value lookup (used by inlay hints, Feign addresses and navigation)
- The Feign gutter icon checks annotations only instead of building the whole client for every method of every interface, repositories included; clicking it resolves the address off the UI thread
- Search Everywhere's Endpoints tab no longer holds a blocking read action, and navigation from it and from the Beans window happens off the UI thread

### Fixes
- Bean, config, endpoint and Feign models now also follow changes in Kotlin code (they only followed Java edits) and library changes

## [0.7.1]

### Fixes
- The profile list of the Endpoints and Feign windows no longer shows profiles from other open projects or from libraries
- The profile list only offers profiles that change an address (of a Feign client, or the application's port or context path)
- Feign addresses in a multi-module project are read from the config of the client's own application module, including `spring.profiles.active`
- With "Default" and no active profile, an address that only profile files define comes from one profile, the first by name, and its source says which; values are no longer mixed from several profiles or picked in index order

## [0.7.0]

### Spring Data JPA
- `@Query` strings know your entities (Java and Kotlin, jakarta and javax): completion of entity names, of attributes after `alias.` (through joins and embeddables) and of `:parameter` names; Ctrl+Click to the entity, attribute or method parameter; renaming one of them updates the query
- Inspection: unknown entity or attribute in a JPQL `@Query`. Inherited attributes, `#{#entityName}` and Hibernate's `id`, `size` and subtype attributes are understood; native queries are not checked against entities
- Inspection: `@Query` parameters that are not bound (`:name` or `?n` with no method parameter, named and positional mixed) or not used, in JPQL and native queries; `Pageable`, `Sort` and SpEL parameters are taken into account

### Changed
- HTTP Response: response headers fold behind a "Show headers (n)" toggle (open by default for up to three headers), the body gets the rest of the window
- Feign clients show their address on a second line; the profile chooser is labelled
- Recordings: larger request summary, status and duration moved to the facts line, selected row in bold

## [0.6.0]

### Recordings
- Filter the list by path or show only failed requests; "Show Route Recordings" in the Endpoints context menu lists the recordings of one endpoint, and "Go to Controller" opens the handler of a recording
- SQL tab for incoming requests (opt-in, `tendril.recorder.sql=true`): count and total time of the JDBC prepared statements run while serving the request, per statement template; bound parameters are not collected

## [0.5.0]

### Changed
- Tool windows use the IDE's own tab strip and lighter chrome: hairlines instead of boxes, headers and body of a request or response in one view, link-style actions in Recordings
- Incoming and Outgoing are icon tabs with tooltips; the Endpoints list is always grouped by controller
- Actuator: the address is read from the config files (`management.server.port`, `management.endpoints.web.base-path`) instead of a fixed default, and empty states are one short line
- The Spring Beans tool window is switched off for now; bean gutter icons and inspections are unchanged

### Fixes
- Send Request from the Endpoints window no longer logs a "Write-unsafe context" error
- Endpoints and Actuator tool window icons turn white when selected in dark themes
- The "Keep last" field of Recordings shows three-digit numbers

## [0.4.0]

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
