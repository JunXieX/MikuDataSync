import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer

val commonProject = project(":common")

dependencies {
    implementation(commonProject)
    compileOnly("com.velocitypowered:velocity-api:4.3.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.3.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}

tasks.jar {
    dependsOn(commonProject.tasks.named("classes"))
    from(commonProject.extensions.getByType<SourceSetContainer>().getByName("main").output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
