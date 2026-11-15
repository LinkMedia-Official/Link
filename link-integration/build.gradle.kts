plugins { java }
repositories { maven("https://libraries.minecraft.net/") }
dependencies {
    implementation(project(":aspaper-server"))
    implementation(project(":aspaper-api"))
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":link-core"))
    compileOnly("com.mojang:authlib:10.0.77")
    compileOnly("com.mojang:datafixerupper:10.0.21")
    compileOnly("io.netty:netty-transport:4.2.16.Final")
}
