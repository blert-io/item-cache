import groovy.json.JsonSlurper
import java.net.URL

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.0.0-beta13"
}

group = "io.blert"
version = "1.0"

repositories {
    mavenCentral()

    maven {
        url = uri("https://repo.runelite.net")
        content {
            includeGroup("net.runelite")
        }
    }
}

// Resolve the RuneLite version from what the bootstrap file advertises.
var latestRl: String? = null
fun getLatestRunelite(): String {
    if (latestRl != null) {
        return latestRl!!
    }
    val jsonText = URL("https://static.runelite.net/bootstrap.json").readText()
    val json = JsonSlurper().parseText(jsonText) as Map<*, *>
    latestRl = json["version"] as String
    return latestRl!!
}

dependencies {
    implementation("net.runelite:cache:${getLatestRunelite()}")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("commons-cli:commons-cli:1.6.0")
    implementation("org.slf4j:slf4j-api:1.7.36")
    runtimeOnly("org.slf4j:slf4j-simple:1.7.36")
}

// Target Java 11 like RuneLite.
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(11)
}

tasks.shadowJar {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "io.blert.itemcache.ItemDumper"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
