plugins {
    id("java-common")
    id("antlr-grammar")
}
dependencies {
    compile(project(":theta-core"))
    compile(project(":theta-algorithmselection"))
}