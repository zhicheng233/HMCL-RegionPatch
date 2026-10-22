package org.jackhuang.hmcl.patch

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.implementation.FixedValue
import net.bytebuddy.matcher.ElementMatchers
import net.bytebuddy.utility.JavaModule
import java.io.File
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

/**
 * Java agent that replaces the body of
 * `org.jackhuang.hmcl.util.RegionUtils.isIsChinaMainlandMachine()` with
 * `return false;`.
 *
 * Design notes:
 *  * Only that one method is instrumented. `IS_CHINA_MAINLAND_MACHINE` and
 *    `isIsChinaMainlandNetwork()` are left untouched, so code that reads the
 *    field directly keeps its original behaviour.
 *  * `disableClassFormatChanges()` keeps the class file structurally identical
 *    (no added/removed fields or methods), which is what makes retransforming an
 *    already-loaded class legal.
 *  * `RedefinitionStrategy.RETRANSFORMATION` makes the patch apply both to
 *    classes defined after `premain` and to classes the JVM already loaded.
 *  * "agent initialised" is logged separately from "PATCH APPLIED"; the latter
 *    is emitted from the Byte Buddy listener only after bytecode was actually
 *    rewritten, so a missing/renamed target class is visible instead of being
 *    reported as success.
 */
object RegionPatchAgent {

    private const val TARGET_CLASS = "org.jackhuang.hmcl.util.RegionUtils"
    private const val TARGET_METHOD_MACHINE = "isIsChinaMainlandMachine"
    private const val TARGET_METHOD_NETWORK = "isIsChinaMainlandNetwork"
    private val TARGET_METHOD_List = listOf(TARGET_METHOD_MACHINE, TARGET_METHOD_NETWORK)

    @JvmStatic
    fun premain(agentArgs: String?, instrumentation: Instrumentation) {
        Log.info("HMCL RegionPatch agent starting (args=${agentArgs ?: "none"})")
        Log.info(
            "JVM ${System.getProperty("java.version")} (${System.getProperty("java.vendor")}), " +
                "retransform supported=${instrumentation.isRetransformClassesSupported}"
        )
        System.setProperty("regionpatch.agent.loaded", "true")
        System.setProperty("regionpatch.agent.status", "STARTING")

        // Enumerated *before* installing the transformer so that the diagnostic
        // describes the state the agent actually found.
        val loadedClasses = try {
            instrumentation.allLoadedClasses
        } catch (t: Throwable) {
            Log.error("Could not enumerate loaded classes", t)
            emptyArray()
        }
        val alreadyLoaded = loadedClasses.filter { it.name == TARGET_CLASS }
        Log.info(
            "Diagnostics: '$TARGET_CLASS' " + if (alreadyLoaded.isEmpty()) {
                "is not loaded yet (checked ${loadedClasses.size} classes); the patch will be " +
                    "applied when HMCL first defines it."
            } else {
                "was already loaded by " +
                    alreadyLoaded.joinToString { describeLoader(it.classLoader) } +
                    "; it will be retransformed during installation."
            }
        )

        if (!installInstrumentation(instrumentation)) {
            System.setProperty("regionpatch.agent.status", "FAILED")
            return
        }

        Log.info(
            "Agent initialisation finished, status property = " +
                "'${System.getProperty("regionpatch.agent.status")}'. Installation alone does NOT " +
                "prove the method was rewritten - only the status becoming 'PATCHED' does."
        )

        // If the JVM exits without the target class ever having been transformed,
        // say so explicitly at shutdown instead of leaving the operator with an
        // "agent started fine" impression. Written straight to stderr: JUL's own
        // shutdown hook may already have closed its handlers at this point.
        Runtime.getRuntime().addShutdownHook(
            Thread({
                if (System.getProperty("regionpatch.agent.status") != "PATCHED") {
                    System.err.println(
                        "[RegionPatch] ERROR: exiting without patching " +
                            "'$TARGET_CLASS.$TARGET_METHOD_MACHINE()' " +
                            "(status='${System.getProperty("regionpatch.agent.status")}'). " +
                            "The class was never loaded by this JVM, or its signature did not " +
                            "match. Note that HMCL 3.16.x does not contain RegionUtils at all; " +
                            "it only exists in 3.17+ builds."
                    )
                }
            }, "RegionPatch-status-check")
        )
    }

    /**
     * Installs the transformer.
     *
     * @return `true` when the transformer is active and retransformation is
     * supported, `false` when the JVM cannot satisfy the requirements.
     */
    private fun installInstrumentation(instrumentation: Instrumentation): Boolean {
        if (!instrumentation.isRetransformClassesSupported) {
            Log.error(
                "This JVM does not support class retransformation, so an already-loaded " +
                    "'$TARGET_CLASS' could not be patched. Add 'Can-Retransform-Classes: true' " +
                    "to the agent manifest and use a standard HotSpot JVM. Aborting."
            )
            return false
        }

        try {
            AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(LoggingListener(TARGET_METHOD_List))
                .disableClassFormatChanges()
                .type(ElementMatchers.named(TARGET_CLASS))
                .transform(RegionUtilsTransformer(TARGET_METHOD_List))
                .installOn(instrumentation)
        } catch (t: Throwable) {
            Log.error("Failed to install the class file transformer", t)
            return false
        }

        Log.info("Transformer installed (RedefinitionStrategy.RETRANSFORMATION, class format frozen).")
        return true
    }

    /**
     * Rewrites exactly one method, after validating its signature.
     */
    private class RegionUtilsTransformer(private val targetMethods: List<String>) : AgentBuilder.Transformer {
        
        companion object {
            var successMethods = mutableListOf<String >()
        }
        
        override fun transform(
            builder: DynamicType.Builder<*>,
            typeDescription: TypeDescription,
            classLoader: ClassLoader?,
            module: JavaModule?,
            protectionDomain: ProtectionDomain?,
        ): DynamicType.Builder<*> {
            var result = builder
            for (targetMethod in targetMethods) {
                val candidates = typeDescription.declaredMethods.filter {
                    it.name == targetMethod && it.parameters.isEmpty()
                }

                if (candidates.isEmpty()) {
                    Log.error(
                        "Class '$TARGET_CLASS' has no zero-argument method named '$targetMethod'. " +
                                "Nothing was patched. Declared methods: " +
                                typeDescription.declaredMethods.joinToString { it.name }
                    )
                    System.setProperty("regionpatch.agent.status", "TARGET_METHOD_MISSING")
                    continue
                }

                val target = candidates.first()
                val problems = validate(target)
                if (problems.isNotEmpty()) {
                    Log.error(
                        "Method '$TARGET_CLASS.$targetMethod' has an unexpected signature " +
                                "(modifiers=${target.modifiers}, returns=${target.returnType}, " +
                                "parameters=${target.parameters.size}). Refusing to patch: " +
                                problems.joinToString("; ")
                    )
                    System.setProperty("regionpatch.agent.status", "TARGET_SIGNATURE_MISMATCH")
                    continue
                }
                
                Log.info(
                    "Target validated: public static boolean $targetMethod() in '$TARGET_CLASS' " +
                            "(loader=${describeLoader(classLoader)})"
                )
                Log.info("Replacing body of '$TARGET_CLASS.$targetMethod' with 'return false'.")
            
                result = result
                    .method(
                        ElementMatchers.named<MethodDescription>(targetMethod)
                            .and(ElementMatchers.takesNoArguments<MethodDescription>())
                            .and(ElementMatchers.isStatic<MethodDescription>())
                            .and(ElementMatchers.returns<MethodDescription>(java.lang.Boolean.TYPE))
                    )
                    .intercept(FixedValue.value(false))
                successMethods.addLast(targetMethod)
            }   
            return result;
        }

        private fun validate(method: MethodDescription): List<String> {
            val problems = mutableListOf<String>()
            if (!method.isPublic) problems += "method is not public"
            if (!method.isStatic) problems += "method is not static"
            if (!method.returnType.represents(java.lang.Boolean.TYPE)) {
                problems += "return type is ${method.returnType.typeName}, expected boolean"
            }
            if (!method.parameters.isEmpty()) problems += "method takes parameters"
            return problems
        }

    }

    /**
     * Reports results for the target type only. `onTransformation` is the only
     * signal that bytecode was really rewritten, hence the distinct
     * `PATCH APPLIED` line.
     */
    private class LoggingListener(private val targetMethod: List<String>) : AgentBuilder.Listener {

        override fun onDiscovery(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
        ) {
            if (typeName == TARGET_CLASS) {
                Log.info("Discovered '$typeName' (loaded=$loaded, loader=${describeLoader(classLoader)})")
            }
        }

        override fun onTransformation(
            typeDescription: TypeDescription,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
            dynamicType: DynamicType,
        ) {
            if (typeDescription.name != TARGET_CLASS) return

            System.setProperty("regionpatch.agent.status", "PATCHED")
            var errorMsg = ""
            RegionUtilsTransformer.successMethods.forEach { method -> errorMsg += method + "();"}
            Log.info(
                "PATCH APPLIED: '${typeDescription.name}' rewritten " +
                    "(wasLoaded=$loaded, loader=${describeLoader(classLoader)}). " +
                    "$errorMsg now returns false."
            )
            dumpIfRequested(typeDescription, dynamicType)
        }

        override fun onIgnored(
            typeDescription: TypeDescription,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
        ) {
            // Not interesting for the target class; ignored types are skipped silently.
        }

        override fun onError(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
            throwable: Throwable,
        ) {
            Log.error(
                "Bytecode transformation FAILED for '$typeName' " +
                    "(loaded=$loaded, loader=${describeLoader(classLoader)})",
                throwable,
            )
            if (typeName == TARGET_CLASS) {
                System.setProperty("regionpatch.agent.status", "FAILED")
            }
        }

        override fun onComplete(
            typeName: String,
            classLoader: ClassLoader?,
            module: JavaModule?,
            loaded: Boolean,
        ) {
            if (typeName == TARGET_CLASS) {
                Log.info("Transformation pass completed for '$typeName' (loaded=$loaded).")
            }
        }

        /**
         * Optional diagnostic: `-Dregionpatch.dumpDir=<dir>` writes the rewritten
         * class file so it can be inspected with `javap -p -c`. Off by default.
         */
        private fun dumpIfRequested(typeDescription: TypeDescription, dynamicType: DynamicType) {
            val dir = System.getProperty("regionpatch.dumpDir")
            if (dir.isNullOrBlank()) return

            try {
                val bytes = dynamicType.bytes
                val target = File(dir, "${typeDescription.name}.class")
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                Log.info("Dumped rewritten bytecode to '${target.absolutePath}'.")
            } catch (t: Throwable) {
                Log.error("Could not dump the rewritten class file", t)
            }
        }
    }

    private fun describeLoader(loader: ClassLoader?): String =
        loader?.javaClass?.name ?: "bootstrap"

    /**
     * Minimal logger built on {@code java.util.logging} (no extra dependency).
     * Output is deliberately compact so that a launcher console stays readable:
     * {@code [RegionPatch] INFO: message}.
     */
    internal object Log {
        private val logger: java.util.logging.Logger =
            java.util.logging.Logger.getLogger("RegionPatch").also { log ->
                log.useParentHandlers = false
                log.level = java.util.logging.Level.INFO
                log.addHandler(
                    java.util.logging.ConsoleHandler().apply {
                        level = java.util.logging.Level.INFO
                        formatter = object : java.util.logging.Formatter() {
                            override fun format(record: java.util.logging.LogRecord): String {
                                val thrown = record.thrown?.let {
                                    "\n" + it.stackTraceToString().trimEnd()
                                } ?: ""
                                return "[RegionPatch] ${record.level.name}: ${record.message}$thrown\n"
                            }
                        }
                    }
                )
            }

        fun info(message: String) = logger.info(message)

        fun error(message: String, cause: Throwable? = null) {
            if (cause == null) logger.severe(message)
            else logger.log(java.util.logging.Level.SEVERE, message, cause)
        }
    }
}
