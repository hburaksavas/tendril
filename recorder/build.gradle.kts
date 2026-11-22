plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    // Everything is compileOnly: the recorder runs inside the application and uses the application's own classes.
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:3.5.7"))
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
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // ControlServer mentions ApplicationContext in its signature
    testRuntimeOnly(platform("org.springframework.boot:spring-boot-dependencies:3.5.7"))
    testRuntimeOnly("org.springframework:spring-context")
}

tasks.test {
    useJUnitPlatform()
}
