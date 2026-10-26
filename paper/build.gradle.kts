import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer

val commonProject = project(":common")

dependencies {
    // common is compiled into this jar (see tasks.jar); it must not also arrive as a runtime
    // dependency, otherwise the same classes would be on the classpath twice.
    compileOnly(commonProject)
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    testImplementation(commonProject)
    testImplementation("io.papermc.paper:paper-api:26.3.build.+")
}

tasks.jar {
    archiveBaseName.set("MikuDataSync-Paper")
    dependsOn(commonProject.tasks.named("classes"))
    from(commonProject.extensions.getByType<SourceSetContainer>().getByName("main").output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    val properties = mapOf("version" to project.version.toString())
    inputs.properties(properties)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand(properties)
    }
}
