plugins {
    id("java")
    id("application")
    id("me.champeau.jmh") version "0.7.2"
}

group = "org.example"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_23
    targetCompatibility = JavaVersion.VERSION_23
}

application {
    mainClass = "org.greeps.clob.Main"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.eclipse.collections:eclipse-collections:11.1.0")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.test {
    useJUnitPlatform()
}

jmh {
    warmupIterations = 5
    iterations = 10
    fork = 1
}