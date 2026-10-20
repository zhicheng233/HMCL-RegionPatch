package regionpatch.preload;

import java.lang.instrument.Instrumentation;

/**
 * Test-only agent that forces {@code RegionUtils} to be loaded before the
 * RegionPatch agent runs, so the retransformation path
 * ({@code RedefinitionStrategy.RETRANSFORMATION} on an already-loaded class) is
 * covered by an automated test and not just by a manual experiment.
 *
 * <p>It is never shipped; it only exists inside the build directory.
 */
public final class PreloadAgent {

    public static void premain(String args, Instrumentation instrumentation) throws Exception {
        Class<?> type = Class.forName("org.jackhuang.hmcl.util.RegionUtils");
        System.out.println("[Preload] loaded " + type.getName() + " before RegionPatch");
        System.out.println("[Preload] isIsChinaMainlandMachine() (unpatched) = "
                + type.getMethod("isIsChinaMainlandMachine").invoke(null));
        System.setProperty("regionpatch.harness.preloaded", "true");
    }

    private PreloadAgent() {
    }
}
