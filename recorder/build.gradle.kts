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
