plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
dependencies {
    api("com.google.protobuf:protobuf-java:4.36.2")
    implementation("com.google.guava:guava:33.3.1-android")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
}
