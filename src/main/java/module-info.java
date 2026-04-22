module io.github.ralfspoeth.log.weaver {
    requires static maven.plugin.api;
    requires static maven.plugin.annotations;
    requires static io.github.ralfspoeth.log.api;
    exports io.github.ralfspoeth.log.weaver;
}