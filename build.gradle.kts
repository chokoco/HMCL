buildscript {
    repositories {
        mavenCentral()
        maven(url = "https://plugins.gradle.org/m2/")
    }

    dependencies {
        classpath("com.google.code.gson:gson:2.8.1")
    }
}

plugins {
    id("checkstyle")
}

group = "org.jackhuang"
version = "3.0"

subprojects {
    apply {
        plugin("java")
        plugin("idea")
        plugin("maven-publish")
        plugin("checkstyle")
    }

    repositories {
        flatDir {
            name = "libs"
            dirs = setOf(rootProject.file("lib"))
        }
        mavenCentral()
        maven(url = "https://jitpack.io")
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"

        options.encoding = "UTF-8"
    }

    configure<CheckstyleExtension> {
        sourceSets = setOf()
    }

    tasks.withType<Checkstyle> {
        exclude("de/javawi/jstun")
    }

    dependencies {
        "testImplementation"("junit:junit:4.12")
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
        repositories {
            mavenLocal()
        }
    }
}

tasks.create("checkTranslations") {
    doLast {
        val hmclLangDir = file("HMCL/src/main/resources/assets/lang")

        val en = java.util.Properties().apply {
            hmclLangDir.resolve("I18N.properties").bufferedReader().use { load(it) }
        }

        val zh = java.util.Properties().apply {
            hmclLangDir.resolve("I18N_zh.properties").bufferedReader().use { load(it) }
        }

        val zh_CN = java.util.Properties().apply {
            hmclLangDir.resolve("I18N_zh_CN.properties").bufferedReader().use { load(it) }
        }

        var success = true

        zh_CN.forEach {
            if (!en.containsKey(it.key)) {
                project.logger.warn("I18N.properties missing key '${it.key}'")
                success = false
            }
        }

        zh_CN.forEach {
            if (!zh.containsKey(it.key)) {
                project.logger.warn("I18N_zh.properties missing key '${it.key}'")
                success = false
            }
        }

        if (!success) {
            throw GradleException("Part of the translation is missing")
        }
    }
}

defaultTasks("clean", "build")

data class Platform(
    val name: String,
    val classifier: String,
    val groupId: String = "org.openjfx",
    val unsupportedModules: List<String> = listOf()
) {
    val modules: List<String> = jfxModules.filter { it !in unsupportedModules }

    fun fileUrl(
        module: String, classifier: String, ext: String,
        repo: String = "https://repo1.maven.org/maven2"
    ): java.net.URL =
        java.net.URL(
            "$repo/${groupId.replace('.', '/')}/javafx-$module/$jfxVersion/javafx-$module-$jfxVersion-$classifier.$ext"
        )
}

val jfxModules = listOf("base", "graphics", "controls", "fxml", "media", "web")
val jfxVersion = "17"
val jfxMirrorRepos = listOf("https://maven.aliyun.com/repository/central")
val jfxDependenciesFile = project("HMCL").buildDir.resolve("openjfx-dependencies.json")
val jfxPlatforms = listOf(
    Platform("windows-x86", "win-x86"),
    Platform("windows-x86_64", "win"),
    Platform("osx-x86_64", "mac"),
    Platform("osx-arm64", "mac-aarch64"),
    Platform("linux-x86_64", "linux"),
    Platform("linux-arm32", "linux-arm32-monocle", unsupportedModules = listOf("media", "web")),
    Platform("linux-arm64", "linux-aarch64"),
)

val jfxInClasspath =
    try {
        Class.forName("javafx.application.Application", false, this.javaClass.classLoader)
        true
    } catch (ignored: Throwable) {
        false
    }

if (!jfxInClasspath && JavaVersion.current() >= JavaVersion.VERSION_11) {
    val os = System.getProperty("os.name").toLowerCase().let { osName ->
        when {
            osName.contains("win") -> "win"
            osName.contains("mac") -> "osx"
            osName.contains("linux") || osName.contains("unix") -> "linux"
            else -> null
        }
    }

    val arch = when (System.getProperty("os.arch").toLowerCase()) {
        "x86_64", "x86-64", "amd64", "ia32e", "em64t", "x64" -> "x86_64"
        "x86", "x86_32", "x86-32", "i386", "i486", "i586", "i686", "i86pc", "ia32", "x32" -> "x86"
        "arm64", "aarch64", "armv8", "armv9" -> "arm64"
        else -> null
    }

    if (os != null && arch != null) {
        val platform = jfxPlatforms.find { it.name == "$os-arch" }
        if (platform != null) {
            val groupId = platform.groupId
            val classifier = platform.classifier
            rootProject.subprojects {
                for (module in jfxModules) {
                    dependencies.add("compileOnly", "$groupId:javafx-$module:$jfxVersion:$classifier")
                }
            }
        }
    }
}

rootProject.tasks.create("generateOpenJFXDependencies") {
    outputs.file(jfxDependenciesFile)

    doLast {
        val jfxDependencies = jfxPlatforms.associate { platform ->
            platform.name to platform.modules.map { module ->
                mapOf(
                    "module" to "javafx.$module",
                    "groupId" to platform.groupId,
                    "artifactId" to "javafx-$module",
                    "version" to jfxVersion,
                    "classifier" to platform.classifier,
                    "sha1" to platform.fileUrl(module, platform.classifier, "jar.sha1").readText()
                )
            }
        }

        jfxDependenciesFile.parentFile.mkdirs()
        jfxDependenciesFile.writeText(
            com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(jfxDependencies)
        )
    }
}

// Ensure that the mirror repository caches files
rootProject.tasks.create("preTouchOpenJFXDependencies") {
    doLast {
        for (repo in jfxMirrorRepos) {
            for (platform in jfxPlatforms) {
                for (module in platform.modules) {
                    val url = platform.fileUrl(module, platform.classifier, "jar")
                    try {
                        url.readBytes()
                    } catch (e: Throwable) {
                        logger.warn("An exception occurred while pre touching $url", e)
                    }
                }
            }
        }
    }
}