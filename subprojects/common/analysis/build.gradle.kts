plugins {
    id("java-common")
}

dependencies {
    implementation(project(":theta-common"))
    implementation(project(":theta-core"))
    implementation(project(":theta-solver"))
    implementation(Deps.javasmt)
    implementation(project(":theta-solver-javasmt"))
    implementation(project(":theta-solver-z3-legacy"))
    implementation(project(":theta-graph-solver"))
    implementation(project(":theta-xta"))
    implementation(project(mapOf("path" to ":theta-solver-z3-legacy")))
    testImplementation(project(":theta-solver-z3-legacy"))
    testImplementation(project(":theta-solver-z3"))
}
