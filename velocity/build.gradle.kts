import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer

val commonProject = project(":common")

dependencies {
    // common is compiled into this jar (see tasks.jar); it must not also arrive as a runtime
    // dependency, otherwise the same classes would be on the classpath twice.
    compileOnly(commonProject)
    compileOnly("com.velocitypowered:velocity-api:4.3.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.3.0")
    testImplementation(commonProject)
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}

// The @Plugin annotation ends up in velocity-plugin.json, which makes it a second source of truth
// for the version next to gradle.properties. Catch the drift at build time instead of shipping a
// plugin whose reported version is older than the jar.
val velocityPluginSource = layout.projectDirectory
    .file("src/main/java/com/junxiex/mikudatasync/velocity/MikuDataSyncVelocity.java")
val verifyPluginVersion = tasks.register("verifyPluginVersion") {
    val source = velocityPluginSource.asFile
    val expected = project.version.toString()
    inputs.file(source)
    doLast {
        val declared = Regex("version = \"([^\"]+)\"")
            .find(source.readText())
            ?.groupValues
            ?.get(1)
        if (declared != expected) {
            throw GradleException(
                "velocity @Plugin version '" + declared + "' does not match project.version '" + expected + "'"
            )
        }
    }
}

tasks.named("check") {
    dependsOn(verifyPluginVersion)
}

tasks.jar {
    archiveBaseName.set("MikuDataSync-Velocity")
    dependsOn(commonProject.tasks.named("classes"))
    from(commonProject.extensions.getByType<SourceSetContainer>().getByName("main").output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
