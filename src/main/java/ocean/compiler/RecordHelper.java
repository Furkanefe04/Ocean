package ocean.compiler;

import org.objectweb.asm.Type;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Utility for resolving record / data class deconstruction components.
 */
public final class RecordHelper {

    private RecordHelper() {}

    public static List<CompilerRegistry.RecordComponentInfo> getRecordComponents(String typeDescOrInternalName) {
        if (typeDescOrInternalName == null || typeDescOrInternalName.isEmpty()) return null;

        String clean = TypeChecker.getInternalName(typeDescOrInternalName);
        if (clean == null || clean.isEmpty()) return null;

        // 1. Check Ocean globalRecordComponents registry
        List<CompilerRegistry.RecordComponentInfo> list = CompilerRegistry.globalRecordComponents.get(clean);
        if (list != null && !list.isEmpty()) return list;

        String fqcn = clean.replace('/', '.');
        list = CompilerRegistry.globalRecordComponents.get(fqcn);
        if (list != null && !list.isEmpty()) return list;

        // 2. Check Java Reflection (for Java 16+ records on classpath)
        try {
            Class<?> clazz = null;
            try {
                clazz = OceanTypeSystem.forName(fqcn);
            } catch (Throwable ignored) {}
            if (clazz != null && clazz.isRecord()) {
                RecordComponent[] rcs = clazz.getRecordComponents();
                if (rcs != null) {
                    List<CompilerRegistry.RecordComponentInfo> components = new CopyOnWriteArrayList<>();
                    for (RecordComponent rc : rcs) {
                        components.add(new CompilerRegistry.RecordComponentInfo(rc.getName(), Type.getDescriptor(rc.getType())));
                    }
                    CompilerRegistry.globalRecordComponents.put(clean, components);
                    CompilerRegistry.globalRecordComponents.put(fqcn, components);
                    return components;
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    public static boolean isRecordOrDataClass(String typeDescOrInternalName) {
        return getRecordComponents(typeDescOrInternalName) != null;
    }

    public static boolean isJavaRecord(String typeDescOrInternalName) {
        if (typeDescOrInternalName == null || typeDescOrInternalName.isEmpty()) return false;
        String clean = TypeChecker.getInternalName(typeDescOrInternalName);
        if (clean == null || clean.isEmpty()) return false;
        String fqcn = clean.replace('/', '.');
        try {
            Class<?> clazz = OceanTypeSystem.forName(fqcn);
            return clazz != null && clazz.isRecord();
        } catch (Throwable ignored) {
            return false;
        }
    }
}