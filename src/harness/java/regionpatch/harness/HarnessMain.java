package regionpatch.harness;

import org.jackhuang.hmcl.util.RegionUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone behaviour test, executed in a dedicated JVM.
 *
 * <p>Expectation is selected with {@code -Dregionpatch.expect=unpatched|patched}:
 *
 * <ul>
 *   <li>{@code unpatched}: no agent, both methods return {@code true}.</li>
 *   <li>{@code patched}: agent loaded via {@code -javaagent}, only
 *       {@code isIsChinaMainlandMachine()} returns {@code false}.</li>
 * </ul>
 *
 * Exit code 0 means every assertion passed.
 */
public final class HarnessMain {

    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String expectation = System.getProperty("regionpatch.expect", "unpatched");
        boolean patched = expectation.toLowerCase(java.util.Locale.ROOT).startsWith("patched");
        boolean expectPreloaded = "patched-retransform".equalsIgnoreCase(expectation);

        System.out.println("[Harness] expectation = " + expectation);
        System.out.println("[Harness] java.version = " + System.getProperty("java.version"));
        System.out.println("[Harness] target loaded by = " + describe(RegionUtils.class.getClassLoader()));
        System.out.println("[Harness] target preloaded before agent = "
                + Boolean.getBoolean("regionpatch.harness.preloaded"));

        if (patched) {
            check("agent is registered with the JVM", agentLoaded());
        }
        if (expectPreloaded) {
            check("fixture class was already loaded before the agent ran",
                    Boolean.getBoolean("regionpatch.harness.preloaded"));
        }

        // 1. The real target method.
        boolean machine = RegionUtils.isIsChinaMainlandMachine();
        System.out.println("[Harness] isIsChinaMainlandMachine() = " + machine);
        check("isIsChinaMainlandMachine() == " + !patched, machine == !patched);

        // 2. The near-name neighbour must never be touched.
        boolean network = RegionUtils.isIsChinaMainlandNetwork();
        System.out.println("[Harness] isIsChinaMainlandNetwork() = " + network);
        check("isIsChinaMainlandNetwork() is unchanged (true)", network);

        // 3. The static field must never be touched.
        boolean field = RegionUtils.IS_CHINA_MAINLAND_MACHINE;
        System.out.println("[Harness] IS_CHINA_MAINLAND_MACHINE = " + field);
        check("IS_CHINA_MAINLAND_MACHINE is unchanged (true)", field);

        // 4. Bytecode-level check: exactly one method changed shape/behaviour, and
        //    the patched method must no longer read the field at all.
        Method target = RegionUtils.class.getDeclaredMethod("isIsChinaMainlandMachine");
        check("target method is public", java.lang.reflect.Modifier.isPublic(target.getModifiers()));
        check("target method is static", java.lang.reflect.Modifier.isStatic(target.getModifiers()));
        check("target method has no parameters", target.getParameterCount() == 0);
        check("target method returns boolean", target.getReturnType() == boolean.class);

        Method networkMethod = RegionUtils.class.getDeclaredMethod("isIsChinaMainlandNetwork");
        check("neighbour method is still public static", java.lang.reflect.Modifier.isPublic(networkMethod.getModifiers())
                && java.lang.reflect.Modifier.isStatic(networkMethod.getModifiers()));
        if (patched) {
            check("neighbour method body is untouched (still reads the field)",
                    readsField(networkMethod, "IS_CHINA_MAINLAND_MACHINE"));
            check("target method body no longer reads the field",
                    !readsField(target, "IS_CHINA_MAINLAND_MACHINE"));
        }

        // 5. Class structure must be preserved (disableClassFormatChanges).
        check("no fields were added or removed", RegionUtils.class.getDeclaredFields().length == 1);
        check("no methods were added or removed", RegionUtils.class.getDeclaredMethods().length == 2);

        // 6. Determinism: repeated calls stay patched (no lazy state surprises).
        for (int i = 0; i < 3; i++) {
            check("repeated call #" + i + " stayed " + !patched,
                    RegionUtils.isIsChinaMainlandMachine() == !patched);
        }

        if (failures.isEmpty()) {
            System.out.println("[Harness] RESULT: PASS (" + expectation + ")");
        } else {
            System.out.println("[Harness] RESULT: FAIL (" + expectation + ")");
            for (String failure : failures) {
                System.out.println("[Harness]   - " + failure);
            }
            System.exit(1);
        }
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            System.out.println("[Harness]   ok   " + description);
        } else {
            System.out.println("[Harness]   FAIL " + description);
            failures.add(description);
        }
    }

    /**
     * True when this JVM was started with {@code -javaagent} and the agent class
     * is resolvable. Both facts come from the JVM, not from the agent's own log.
     */
    private static boolean agentLoaded() {
        boolean viaArg = java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments()
                .stream()
                .anyMatch(arg -> arg.startsWith("-javaagent:"));
        boolean classVisible;
        try {
            Class.forName("org.jackhuang.hmcl.patch.RegionPatchAgent", false, HarnessMain.class.getClassLoader());
            classVisible = true;
        } catch (Throwable t) {
            classVisible = false;
        }
        System.out.println("[Harness] -javaagent in JVM args = " + viaArg
                + ", agent class visible = " + classVisible);
        return viaArg && classVisible;
    }

    /**
     * Bytecode probe: disassembles the class with {@code javap} and looks for a
     * {@code getstatic} of the given field inside the given method.
     *
     * <p>The disassembly source matters. For a retransformed (in-memory) class,
     * {@code javap -cp <classpath>} would read the <em>original</em> class file
     * from disk and therefore prove nothing. When the agent was asked to dump
     * the rewritten class ({@code -Dregionpatch.dumpDir}), that file is used
     * instead, so the checked bytes really are the ones the JVM loaded.
     */
    private static boolean readsField(Method method, String fieldName) {
        String dumpDir = System.getProperty("regionpatch.dumpDir");
        if (dumpDir == null || dumpDir.isBlank()) {
            System.out.println("[Harness]   note: -Dregionpatch.dumpDir not set, cannot inspect rewritten bytes");
            return false;
        }
        java.io.File dumped = new java.io.File(dumpDir, method.getDeclaringClass().getName() + ".class");
        if (!dumped.isFile()) {
            System.out.println("[Harness]   note: no dumped class file at " + dumped.getAbsolutePath());
            return false;
        }
        try {
            String javaHome = System.getProperty("java.home");
            String javap = javaHome + java.io.File.separator + "bin" + java.io.File.separator + "javap"
                    + (System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "");
            Process process = new ProcessBuilder(javap, "-p", "-c", dumped.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append('\n');
                }
            }
            process.waitFor();

            String body = methodBody(text.toString(), method.getName());
            if (body == null) {
                System.out.println("[Harness]   note: method " + method.getName() + " not found in javap output");
                return false;
            }
            return body.contains("// Field " + fieldName + ":Z");
        } catch (Throwable t) {
            System.out.println("[Harness]   note: bytecode probe failed (" + t + ")");
            return false;
        }
    }

    /**
     * Extracts the javap section of one method. Method signatures are indented by
     * exactly two spaces, whereas bytecode lines are indented further and start
     * with an offset, so the next two-space line terminates the section.
     */
    private static String methodBody(String javapOutput, String methodName) {
        String[] lines = javapOutput.split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("  ") && !line.startsWith("   ") && line.contains(methodName + "(")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (i > start && lines[i].startsWith("  ") && !lines[i].startsWith("   ")) {
                break;
            }
            body.append(lines[i]).append('\n');
        }
        return body.toString();
    }

    private static String describe(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName();
    }

    private HarnessMain() {
    }
}
