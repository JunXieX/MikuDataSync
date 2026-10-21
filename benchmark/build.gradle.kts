plugins {
    application
}

dependencies {
    implementation(project(":common"))
}

application {
    mainClass.set("com.junxiex.mikudatasync.benchmark.CodecBenchmark")
}
