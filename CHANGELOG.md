# Changelog

All notable changes to Tendril. The section of the version being built becomes the "What's New" text of the plugin.

## [Unreleased]

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
