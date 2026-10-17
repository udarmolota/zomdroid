package com.zomdroid;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// The DWARF side was checked against the game's real ARM64 libraries (42.20 Lighting: updateTorch
// C 11 vs Java 14; everything in 42.21 clean); those files cannot live in this repository.
public class JniAbiCheckTest {

    /** Natives in the shapes the game uses: overloads, arrays, objects. */
    @SuppressWarnings("unused")
    static class Natives {
        static native void updateTorch(int id, float x, float y, float z, float r, float g, float b,
                                       float ax, float ay, float dist, float strength, boolean cone,
                                       float dot, int focus);
        static native int get(long handle);
        static native int get(long handle, String name, int[] out);
        native Object[] plain_name();
        void notNative() {}
    }

    @Test
    public void mangleAndDemangleFollowTheJniRules() {
        assertEquals("zombie_iso_LightingJNI", JniAbiCheck.mangle("zombie/iso/LightingJNI"));
        assertEquals("FMOD_1System_1Get3DSettings", JniAbiCheck.mangle("FMOD_System_Get3DSettings"));
        assertArrayEquals(new String[] {"zombie/iso/LightingJNI", "updateTorch"},
                JniAbiCheck.demangle("Java_zombie_iso_LightingJNI_updateTorch"));
        assertArrayEquals(new String[] {"fmod/javafmodJNI", "FMOD_System_Get3DSettings"},
                JniAbiCheck.demangle("Java_fmod_javafmodJNI_FMOD_1System_1Get3DSettings__JJ"));
        assertNull(JniAbiCheck.demangle("not_jni"));
    }

    @Test
    public void countsJavaArguments() {
        assertEquals(14, JniAbiCheck.argumentCount("(IFFFFFFFFFFZFI)V"));
        assertEquals(3, JniAbiCheck.argumentCount("(JLjava/lang/String;[I)I"));
        assertEquals(1, JniAbiCheck.argumentCount("([[Ljava/lang/Object;)V"));
        assertEquals(0, JniAbiCheck.argumentCount("()[Ljava/lang/Object;"));
    }

    @Test
    public void readsNativeMethodsFromAClassFile() throws Exception {
        List<String[]> natives = JniAbiCheck.nativeMethods(classBytes(Natives.class));
        assertEquals(4, natives.size());
    }

    @Test
    public void overloadsGetTheirLongJniNames() throws Exception {
        File jar = File.createTempFile("natives", ".jar");
        try {
            String path = Natives.class.getName().replace('.', '/');
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar.toPath()))) {
                out.putNextEntry(new ZipEntry(path + ".class"));
                out.write(classBytes(Natives.class));
                out.closeEntry();
            }
            String cls = "Java_" + JniAbiCheck.mangle(path);
            Map<String, Integer> args = JniAbiCheck.javaNativeArgumentCounts(jar,
                    Arrays.asList(cls + "_updateTorch", cls + "_get__J"));
            assertEquals(Integer.valueOf(14), args.get(cls + "_updateTorch"));
            assertEquals(Integer.valueOf(1), args.get(cls + "_get__J"));
            assertEquals(Integer.valueOf(3), args.get(cls + "_get__JLjava_lang_String_2_3I"));
            assertEquals(Integer.valueOf(0), args.get(cls + "_plain_1name"));
            assertNull(args.get(cls + "_get"));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            jar.delete();
        }
    }

    @Test
    public void unreadableLibraryIsUnknownNotWrong() throws Exception {
        File junk = File.createTempFile("junk", ".so");
        try {
            Files.write(junk.toPath(), new byte[] {1, 2, 3});
            assertNull(JniAbiCheck.mismatches(junk, junk));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            junk.delete();
        }
    }

    private static byte[] classBytes(Class<?> c) throws Exception {
        String res = "/" + c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getResourceAsStream(res); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
