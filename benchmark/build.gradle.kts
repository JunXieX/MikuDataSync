plugins {
    application
}

dependencies {
    implementation(project(":common"))
    implementation(project(":velocity"))
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass.set("com.junxiex.mikudatasync.benchmark.AsyncBenchmark")
}
