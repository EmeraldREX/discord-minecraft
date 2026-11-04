plugins { `java-library` }

dependencies {
    api("org.xerial:sqlite-jdbc:3.47.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
