import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.jar.JarFile

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

group = "org.jackhuang.hmcl.patch"
version = "1.0.0"

val byteBuddyVersion: String by project

// HMCL 3.17 ships Java 17 class files (major version 61), so the agent targets
// Java 17 too and deliberately avoids any JDK API newer than 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    // byte-buddy-agent is intentionally NOT used: premain already receives the
    // Instrumentation instance, so no self-attach and no jdk.attach module.
}

// ---------------------------------------------------------------------------
// Test fixtures
//
// `fixture` holds a class with the *real* HMCL fully-qualified name and the same
// shape as the production method, so the agent is exercised through its real
// class/method matcher rather than a test-only hook.
//
// `harness` is plain Java (no Kotlin runtime) and runs in a bare JVM.
// ---------------------------------------------------------------------------

val fixtureSourceSet = sourceSets.create("fixture") {
    java.srcDir("src/fixture/java")
    compileClasspath = files()
}

val harnessSourceSet = sourceSets.create("harness") {
    java.srcDir("src/harness/java")
    compileClasspath += fixtureSourceSet.output
    runtimeClasspath += fixtureSourceSet.output
}

// A throwaway agent that pre-loads the fixture class so the retransformation
// path is exercised automatically instead of only by hand.
val preloadSourceSet = sourceSets.create("preload") {
    java.srcDir("src/preload/java")
    compileClasspath += fixtureSourceSet.output
    runtimeClasspath += fixtureSourceSet.output
}

val preloadAgentJar by tasks.registering(Jar::class) {
    archiveBaseName.set("RegionPatchPreloadAgent")
    archiveVersion.set("")
    destinationDirectory.set(layout.buildDirectory.dir("smoke/lib"))
    from(preloadSourceSet.output)
    manifest {
        attributes(
            "Premain-Class" to "regionpatch.preload.PreloadAgent",
            "Can-Retransform-Classes" to "true",
        )
    }
}

// ---------------------------------------------------------------------------
// Smoke tests
//
// They run in real, separate JVMs: the Gradle daemon must not be started with
// -javaagent, and asserting on behaviour (not on log text) is the only way to
// prove the patch actually happened.
// ---------------------------------------------------------------------------

val smokeTest by tasks.registering {
    description = "Verifies the agent JAR manifest and its behaviour in separate JVMs."
    group = "verification"
    dependsOn(
        tasks.named("shadowJar"),
        tasks.named("harnessClasses"),
        preloadAgentJar,
    )

    val agentJar = layout.buildDirectory.file("libs/RegionPatch.jar")
    val preloadJar = preloadAgentJar.flatMap { it.archiveFile }
    val dumpDir = layout.buildDirectory.dir("smoke/dump")
    val runClasspath = files(fixtureSourceSet.output, harnessSourceSet.output)
    // Resolved at configuration time on purpose: touching a SourceSet from inside
    // doLast would capture the build script instance and break the configuration
    // cache (Gradle rejects "Gradle script object references").
    val harnessOnlyClasspath = harnessSourceSet.output.files.toList()

    doLast {
        val jar = agentJar.get().asFile
        require(jar.isFile) { "Missing agent JAR: $jar (run shadowJar first)" }

        // --- 1. Manifest ---------------------------------------------------
        JarFile(jar).use { jarFile ->
            val manifest = jarFile.manifest
            val expected = linkedMapOf(
                "Premain-Class" to "org.jackhuang.hmcl.patch.RegionPatchAgent",
                "Can-Retransform-Classes" to "true",
            )
            expected.forEach { (key, value) ->
                val actual = manifest.mainAttributes.getValue(key)
                require(actual == value) { "Manifest attribute $key = '$actual', expected '$value'" }
            }
            logger.lifecycle(
                "Manifest OK: " + expected.entries.joinToString { "${it.key}=${it.value}" }
            )
        }

        // --- 2. Behaviour in separate JVMs ---------------------------------
        fun javaExe(): String {
            val home = System.getProperty("java.home")
            val exe = File(home, "bin/java.exe")
            return if (exe.isFile) exe.absolutePath else File(home, "bin/java").absolutePath
        }

        fun runWithClasspath(
            classpath: String,
            mainClass: String,
            vararg jvmArgs: String,
        ): Pair<Int, String> {
            val command = mutableListOf(javaExe())
            command += jvmArgs
            command += listOf("-cp", classpath, mainClass)
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            return process.waitFor() to output
        }

        fun runHarness(vararg jvmArgs: String): Pair<Int, String> =
            runWithClasspath(runClasspath.asPath, "regionpatch.harness.HarnessMain", *jvmArgs)

        val loadTimeDump = dumpDir.get().asFile.resolve("load-time")
        val retransformDump = dumpDir.get().asFile.resolve("retransform")
        loadTimeDump.deleteRecursively()
        retransformDump.deleteRecursively()

        val runs = listOf(
            // Baseline: without the agent nothing may change.
            "no-agent" to runHarness("-Dregionpatch.expect=unpatched"),
            // The agent runs before the fixture class is defined.
            "with-agent" to runHarness(
                "-javaagent:${jar.absolutePath}",
                "-Dregionpatch.expect=patched",
                "-Dregionpatch.dumpDir=${loadTimeDump.absolutePath}",
            ),
            // A helper agent loads the fixture first, so RegionPatch must patch an
            // already-loaded class through retransformation.
            "with-agent-retransform" to runHarness(
                "-javaagent:${preloadJar.get().asFile.absolutePath}",
                "-javaagent:${jar.absolutePath}",
                "-Dregionpatch.expect=patched-retransform",
                "-Dregionpatch.dumpDir=${retransformDump.absolutePath}",
            ),
            // Target class never loads in this JVM (like HMCL 3.16.x, which has no
            // RegionUtils at all): the agent must say so instead of claiming success.
            // The fixture is deliberately left off this class path.
            "target-absent" to runWithClasspath(
                harnessOnlyClasspath.joinToString(File.pathSeparator),
                "regionpatch.harness.NoTargetMain",
                "-javaagent:${jar.absolutePath}",
            ),
        )

        runs.forEach { (name, result) ->
            val (exitCode, output) = result
            logger.lifecycle("----- harness '$name' (exit=$exitCode) -----")
            logger.lifecycle(output.trimEnd())
        }

        val failed = runs.filter { it.second.first != 0 }.map { it.first }
        if (failed.isNotEmpty()) {
            throw GradleException(
                "Smoke test(s) failed: ${failed.joinToString()} (see harness output above)"
            )
        }

        // The "class never loaded" case must not look like success.
        val targetAbsentOutput = runs.first { it.first == "target-absent" }.second.second
        require(targetAbsentOutput.contains("exiting without patching")) {
            "The agent did not report the missing target class at shutdown."
        }
        require(!targetAbsentOutput.contains("PATCH APPLIED")) {
            "The agent claimed a patch although the target class was never loaded."
        }

        // Both transformed bytecode dumps must exist: load-time and retransform.
        listOf("load-time" to loadTimeDump, "retransform" to retransformDump).forEach { (name, dir) ->
            val dumped = dir.resolve("org.jackhuang.hmcl.util.RegionUtils.class")
            require(dumped.isFile) {
                "The agent did not dump rewritten bytecode for the '$name' path at $dumped; " +
                    "the transformation probably never ran."
            }
            logger.lifecycle("Rewritten bytecode ($name) dumped to ${dumped.absolutePath}")
        }

        logger.lifecycle("Smoke tests passed: baseline intact, only isIsChinaMainlandMachine() patched.")
    }
}

tasks.named("check") { dependsOn(smokeTest) }

// ---------------------------------------------------------------------------
// Fat JAR with the agent manifest
// ---------------------------------------------------------------------------

val agentManifest = linkedMapOf(
    "Premain-Class" to "org.jackhuang.hmcl.patch.RegionPatchAgent",
    "Can-Retransform-Classes" to "true",
    "Implementation-Title" to "HMCL RegionPatch Agent",
    "Implementation-Version" to project.version.toString(),
)

tasks.jar {
    archiveBaseName.set("RegionPatch")
    manifest { attributes(agentManifest) }
}

tasks.shadowJar {
    archiveBaseName.set("RegionPatch")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest { attributes(agentManifest) }

    // Relocate Byte Buddy into a private namespace so the agent can never clash
    // with a copy of Byte Buddy on the launcher classpath.
    relocate("net.bytebuddy", "regionpatch.shaded.net.bytebuddy")

    mergeServiceFiles()
}

tasks.named("build") { dependsOn(tasks.named("shadowJar")) }
