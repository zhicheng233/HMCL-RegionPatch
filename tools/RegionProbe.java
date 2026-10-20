import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Standalone probe for a real HMCL jar. It only uses reflection, so it compiles
 * against nothing and can be pointed at any HMCL build.
 *
 * <p>Compile and run (Windows, from the repository root):
 *
 * <pre>
 * javac -d build\probe tools\RegionProbe.java
 * java -cp "build\probe;HMCL.jar" RegionProbe
 * java -javaagent:build\libs\RegionPatch.jar -cp "build\probe;HMCL.jar" RegionProbe
 * </pre>
 *
 * <p>Expected result: without the agent {@code isIsChinaMainlandMachine()} is
 * {@code true}, with the agent it is {@code false}, while
 * {@code IS_CHINA_MAINLAND_MACHINE} and the number of declared methods/fields
 * stay exactly the same.
 */
public final class RegionProbe {

    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("org.jackhuang.hmcl.util.RegionUtils");
        System.out.println("class file major = " + readMajor(type));
        System.out.println("class loader     = " + type.getClassLoader());
        System.out.println("isIsChinaMainlandMachine() = "
                + type.getMethod("isIsChinaMainlandMachine").invoke(null));
        System.out.println("isIsChinaMainlandNetwork()  = "
                + type.getMethod("isIsChinaMainlandNetwork").invoke(null));
        System.out.println("IS_CHINA_MAINLAND_MACHINE   = " + readField(type, "IS_CHINA_MAINLAND_MACHINE"));
        System.out.println("declared methods = " + type.getDeclaredMethods().length);
        System.out.println("declared fields  = " + type.getDeclaredFields().length);
    }

    private static Object readField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            return "<absent>";
        }
    }

    private static int readMajor(Class<?> type) throws Exception {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return -1;
            }
            DataInputStream data = new DataInputStream(in);
            data.readInt();
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }

    private RegionProbe() {
    }
}
