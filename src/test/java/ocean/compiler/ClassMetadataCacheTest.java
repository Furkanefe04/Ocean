package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ClassMetadataCacheTest {

    @BeforeEach
    public void setup() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    @Test
    public void testIsSubtypeCache() {
        assertTrue(ClassMetadataCache.isSubtype("java/lang/String", "java/lang/Object"));
        assertTrue(ClassMetadataCache.isSubtype("java/util/ArrayList", "java/util/List"));
        assertTrue(ClassMetadataCache.isSubtype("java/lang/Integer", "java/lang/Number"));
        assertFalse(ClassMetadataCache.isSubtype("java/lang/Number", "java/lang/Integer"));
        assertFalse(ClassMetadataCache.isSubtype("java/lang/String", "java/util/List"));
    }

    @Test
    public void testIsInterface() {
        assertTrue(ClassMetadataCache.isInterface("java/util/List"));
        assertTrue(ClassMetadataCache.isInterface("java/util/Map"));
        assertTrue(ClassMetadataCache.isInterface("java/lang/Runnable"));
        assertFalse(ClassMetadataCache.isInterface("java/util/ArrayList"));
        assertFalse(ClassMetadataCache.isInterface("java/lang/String"));
    }

    @Test
    public void testSAMInterfaceDetection() {
        assertTrue(ClassMetadataCache.hasSingleAbstractMethod("java/lang/Runnable"));
        assertTrue(ClassMetadataCache.hasSingleAbstractMethod("java/util/function/Function"));
        assertTrue(ClassMetadataCache.hasSingleAbstractMethod("java/util/function/Consumer"));
        assertFalse(ClassMetadataCache.hasSingleAbstractMethod("java/util/List"));
        assertFalse(ClassMetadataCache.hasSingleAbstractMethod("java/util/Map"));

        OceanTypeSystem.SAMMethodInfo runnableSAM = ClassMetadataCache.getSingleAbstractMethodInfo("java/lang/Runnable");
        assertNotNull(runnableSAM);
        assertEquals("run", runnableSAM.name());
        assertEquals("()V", runnableSAM.descriptor());
    }

    @Test
    public void testClassHierarchyAndInterfaces() {
        List<String> arrayListHierarchy = ClassMetadataCache.getClassHierarchy("java/util/ArrayList");
        assertTrue(arrayListHierarchy.contains("java/util/ArrayList"));
        assertTrue(arrayListHierarchy.contains("java/util/AbstractList"));
        assertTrue(arrayListHierarchy.contains("java/lang/Object"));

        List<String> arrayListInterfaces = ClassMetadataCache.getClassInterfaces("java/util/ArrayList");
        assertTrue(arrayListInterfaces.contains("java/util/List"));
        assertTrue(arrayListInterfaces.contains("java/util/RandomAccess"));
        assertTrue(arrayListInterfaces.contains("java/lang/Cloneable"));
        assertTrue(arrayListInterfaces.contains("java/io/Serializable"));
    }

    @Test
    public void testMethodAndConstructorDescriptors() {
        List<String> stringValueMethods = ClassMetadataCache.getPublicMethodDescriptors("java/lang/String", "valueOf");
        assertFalse(stringValueMethods.isEmpty());
        assertTrue(stringValueMethods.contains("(I)Ljava/lang/String;"));

        List<String> arrayListCtors = ClassMetadataCache.getConstructorDescriptors("java/util/ArrayList");
        assertFalse(arrayListCtors.isEmpty());
        assertTrue(arrayListCtors.contains("()V"));
        assertTrue(arrayListCtors.contains("(I)V"));
    }
}