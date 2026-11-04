plugins { id("com.gradleup.shadow") }

dependencies {
    implementation(project(":common"))
    compileOnly("com.velocitypowered:velocity-api:4.3.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.3.0")
    compileOnly(files("../vendor/LunaChat-3.0.20.jar"))
    implementation("net.dv8tion:JDA:5.3.2") {
        exclude(module = "opus-java")
    }
    implementation("org.yaml:snakeyaml:2.3")
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
