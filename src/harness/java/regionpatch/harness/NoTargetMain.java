package regionpatch.harness;

/**
 * A JVM that never touches {@code RegionUtils}.
 *
 * <p>Used to prove that an agent which cannot find its target class reports the
 * failure instead of pretending everything is fine - the situation a user hits
 * when loading this agent into HMCL 3.16.x (which has no {@code RegionUtils}).
 */
public final class NoTargetMain {

    public static void main(String[] args) throws Exception {
        Class.forName("regionpatch.harness.NoTargetMain");
        RegionUtilsLoader.notLoaded();
        System.out.println("[NoTarget] exiting without ever loading RegionUtils");
    }

    private NoTargetMain() {
    }

    /** Keeps the class name out of the constant pool of {@code main}. */
    private static final class RegionUtilsLoader {
        static void notLoaded() {
            System.out.println("[NoTarget] RegionUtils absent from classpath = "
                    + !classPresent());
        }

        private static boolean classPresent() {
            try {
                Class.forName("org.jackhuang.hmcl.util.RegionUtils", false,
                        NoTargetMain.class.getClassLoader());
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
