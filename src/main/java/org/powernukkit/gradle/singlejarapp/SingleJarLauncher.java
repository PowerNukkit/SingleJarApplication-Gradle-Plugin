/*
 * Copyright 2021 José Roberto de Araújo Júnior <joserobjr@powernukkit.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.powernukkit.gradle.singlejarapp;

import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author joserobjr
 * @since 2021-08-14
 */

public class SingleJarLauncher extends SecureClassLoader {
    private static final byte[][] EMPTY = new byte[0][0];
    private final Map<String, byte[][]> knownObjects = new LinkedHashMap<>();
    private byte[] buffer;

    public SingleJarLauncher(String[] internalPaths, ClassLoader parent) throws IOException {
        super(parent);
        for (String internalPath : internalPaths) {
            try (InputStream is = Objects.requireNonNull(getClass().getResourceAsStream(internalPath), "Resource not found: " + internalPath);
                 JarInputStream input = new JarInputStream(is)) {
                JarEntry entry;
                while ((entry = input.getNextJarEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    long size = entry.getSize();
                    byte[] buffer = new byte[size < 0 || size > Integer.MAX_VALUE? 4096 : (int) size];
                    BufferedInputStream bis = new BufferedInputStream(input);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    try {
                        int read;
                        while ((read = bis.read(buffer)) >= 0) {
                            bos.write(buffer, 0, read);
                        }
                    } catch (IOException ignored) {
                        continue;
                    }

                    this.buffer = bos.toByteArray();
                    knownObjects.compute(entry.getName(), this::updateKnownObject);
                    input.closeEntry();
                }
            }
        }
        this.buffer = null;
    }

    private byte[][] updateKnownObject(String key, byte[][] current) {
        if (current == null) {
            return new byte[][] {buffer};
        }
        current = Arrays.copyOf(current, current.length + 1);
        current[current.length - 1] = buffer;
        return current;
    }

    private byte[][] lookup(String name) {
        byte[][] knownPlaces = knownObjects.get(name);
        if (knownPlaces != null) {
            if (knownPlaces.length == 0) {
                return knownPlaces;
            }
            return knownPlaces;
        } else {
            knownObjects.put(name, EMPTY);
            return EMPTY;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String internalClassName = name.replace('.', '/') + ".class";
        byte[][] result = lookup(internalClassName);
        if (result.length == 0) {
            throw new ClassNotFoundException(name);
        }

        return defineClass(name, result[0], 0, result[0].length);
    }

    protected URL createJarInJarURL(String resourceName, byte[] resourceData) {
        URL sendTierUrl;
        try {
            sendTierUrl = new URL(null, "NestedJar:"+resourceName, new URLStreamHandler() {
                InputStream inputStream;

                @Override
                protected URLConnection openConnection(URL url) throws IOException {
                    URLConnection connection = new URLConnection(url) {
                        @Override
                        public void connect() throws IOException {
                            if (inputStream != null) {
                                inputStream.close();
                            }

                            inputStream = new ByteArrayInputStream(resourceData);
                        }
                    };

                    connection.connect();
                    return connection;
                }
            });
        } catch (MalformedURLException ex) {
            throw new UncheckedIOException(ex);
        }

        return sendTierUrl;
    }

    @Override
    protected URL findResource(String name) {
        byte[][] result = lookup(name);
        if (result.length == 0) {
            return null;
        }
        return createJarInJarURL(name, result[0]);
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        byte[][] result = lookup(name);
        if (result.length == 0) {
            return Collections.emptyEnumeration();
        }
        return Collections.enumeration(Stream.of(result).map(bytes-> createJarInJarURL(name, bytes)).collect(Collectors.toList()));
    }

    @Nullable
    @Override
    public InputStream getResourceAsStream(String name) {
        byte[][] result = lookup(name);
        if (result.length == 0) {
            return null;
        }
        return new ByteArrayInputStream(result[0]);
    }

    public static void main(String[] args) throws IOException {
        Class<?> clazz = SingleJarLauncher.class;
        URL jarUrl = clazz.getProtectionDomain().getCodeSource().getLocation();
        String[] libs;
        try (JarFile jarFile = new JarFile(jarUrl.getFile())) {
            try (Stream<JarEntry> entryStream = jarFile.stream()) {
                libs = entryStream.filter(it -> {
                            Path parent = Paths.get(it.getName()).getParent();
                            if (parent == null) {
                                return false;
                            }
                            return parent.equals(Paths.get("META-INF/lib"));
                        })
                        .map(it -> Paths.get("/", it.getName()).toString().replace('\\', '/').replaceFirst("^//", "/"))
                        .toArray(String[]::new);
            }
        }

        SingleJarLauncher loader = new SingleJarLauncher(libs, null);

        URLClassLoader urlClassLoader = new URLClassLoader(new URL[]{jarUrl}, loader);
        String className = clazz.getName();
        className = className.substring(0, className.length() - 18);

        Thread.currentThread().setContextClassLoader(urlClassLoader);

        try {
            Class<?> loadedClass = urlClassLoader.loadClass(className);
            Method main = loadedClass.getDeclaredMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
