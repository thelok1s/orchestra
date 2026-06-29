pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Xposed API (compileOnly)
        maven("https://api.xposed.info/")
        maven("https://jitpack.io")
    }
}
rootProject.name = "Orchestra"
include(":app")
