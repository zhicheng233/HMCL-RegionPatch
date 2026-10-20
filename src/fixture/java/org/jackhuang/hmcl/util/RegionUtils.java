package org.jackhuang.hmcl.util;

/**
 * Test fixture: a stand-in for HMCL's real {@code RegionUtils} with the exact
 * fully-qualified name and the same relevant shape:
 *
 * <ul>
 *   <li>{@code IS_CHINA_MAINLAND_MACHINE} is a {@code static final boolean} that is {@code true};</li>
 *   <li>{@code isIsChinaMainlandMachine()} returns that field;</li>
 *   <li>{@code isIsChinaMainlandNetwork()} also reads the field (a near-name neighbour that must NOT be patched).</li>
 * </ul>
 *
 * The class is deliberately NOT what HMCL ships; it only exists so the agent's
 * real matcher and transformer can be tested in a standalone JVM.
 */
public final class RegionUtils {

    /** Non-constant-foldable at call sites only because callers are in another class. */
    public static final boolean IS_CHINA_MAINLAND_MACHINE = Boolean.parseBoolean("true");

    public static boolean isIsChinaMainlandMachine() {
        return IS_CHINA_MAINLAND_MACHINE;
    }

    /** Near-name neighbour: must keep returning {@code true}. */
    public static boolean isIsChinaMainlandNetwork() {
        return IS_CHINA_MAINLAND_MACHINE;
    }

    private RegionUtils() {
    }
}
