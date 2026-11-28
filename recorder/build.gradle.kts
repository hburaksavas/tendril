plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    // Everything is compileOnly: the recorder runs inside the application and uses the application's own classes.
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:4.2.0"))
    compileOnly("org.springframework.boot:spring-boot")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-context")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("io.github.openfeign:feign-core:13.6")
    compileOnly("org.springframework.amqp:spring-rabbit")
    compileOnly("org.springframework.kafka:spring-kafka")
}

tasks.compileJava {
    options.release = 17
    options.compilerArgs.add("-Xlint:-options")
}

tasks.jar {
    archiveFileName = "tendril-recorder.jar"
    manifest {
        attributes(
            "Premain-Class" to "dev.tendril.recorder.RecorderAgent",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // ControlServer mentions ApplicationContext in its signature
    testRuntimeOnly(platform("org.springframework.boot:spring-boot-dependencies:4.2.0"))
    testRuntimeOnly("org.springframework:spring-context")
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.2.0"))
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testRuntimeOnly("com.h2database:h2")
    testImplementation("io.github.openfeign:feign-core:13.6")
    testImplementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.springframework.amqp:spring-rabbit")
}

tasks.test {
    useJUnitPlatform()
}
