plugins {
    id("com.gradleup.shadow") version "9.2.2" apply false
}

allprojects {
    group = "dev.emeraldrex.discordminecraft"
    version = "0.1.3"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
