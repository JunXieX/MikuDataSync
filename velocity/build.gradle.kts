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

tasks.jar {
    archiveBaseName.set("MikuDataSync-Velocity")
    dependsOn(commonProject.tasks.named("classes"))
    from(commonProject.extensions.getByType<SourceSetContainer>().getByName("main").output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
