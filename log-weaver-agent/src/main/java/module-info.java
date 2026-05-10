module io.github.ralfspoeth.log.weaver.agent {
    exports io.github.ralfspoeth.log.weaver.agent;
    requires static io.github.ralfspoeth.log.api;
    requires io.github.ralfspoeth.log.weaver.core;
    requires java.instrument;
}