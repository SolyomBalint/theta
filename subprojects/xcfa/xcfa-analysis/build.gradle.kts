plugins {
    id("java-common")
}

dependencies {
    compile(project(":theta-cfa-analysis"))
    compile(project(":theta-xcfa"))
    compile(project(":theta-core"))
    compile(project(":theta-common"))
}
