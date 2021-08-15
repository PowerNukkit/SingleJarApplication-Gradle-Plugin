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
import java.time.Duration;
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

public class SingleJarLauncherOkButSlow extends SecureClassLoader {
    private final String[] internalPaths;
    private final Map<String, String[]> knownObjects = new LinkedHashMap<>();
    private String currentInternal;

    public SingleJarLauncherOkButSlow(String[] internalPaths, ClassLoader parent) throws IOException {
        super(parent);
        this.internalPaths = internalPaths;
        long start = System.nanoTime();
        for (String internalPath : internalPaths) {
            currentInternal = internalPath;
            try (InputStream is = Objects.requireNonNull(getClass().getResourceAsStream(internalPath), "Resource not found: " + internalPath);
                 JarInputStream input = new JarInputStream(is)) {
                JarEntry entry;
                while ((entry = input.getNextJarEntry()) != null) {
                    knownObjects.compute(entry.getName(), this::updateKnownObject);
                }
            }
        }
        long stop = System.nanoTime();
        System.out.println("Preloading took " + Duration.ofNanos(start - stop));
    }

    private String[] updateKnownObject(String key, String[] current) {
        if (current == null) {
            return new String[] { currentInternal };
        }
        current = Arrays.copyOf(current, current.length + 1);
        current[current.length - 1] = currentInternal;
        return current;
    }

    private Map<String, Map.Entry<JarInputStream, JarEntry>> lookup(String name, boolean first) throws ClassNotFoundException {
        Queue<Exception> exceptions = null;
        Map<String, Map.Entry<JarInputStream, JarEntry>> result = null;

        String[] scanScope = internalPaths;
        String[] knownPlaces = knownObjects.get(name);
        if (knownPlaces != null) {
            if (knownPlaces.length == 0 || first && (knownPlaces.length == 1 || knownPlaces.length == 2 && knownPlaces[1] == null)) {
                scanScope = knownPlaces;
            } else if (!first && (knownPlaces.length != 2 || knownPlaces[1] != null)) {
                scanScope = knownPlaces;
            }
        }

        nextPath:
        for (String internalPath : scanScope) {
            if (internalPath == null) {
                continue;
            }
            try {
                InputStream input = Objects.requireNonNull(getClass().getResourceAsStream(internalPath), "Nested jar not found: " + internalPath);
                JarInputStream jarInputStream = new JarInputStream(input);
                JarEntry entry;
                while ((entry = jarInputStream.getNextJarEntry()) != null) {
                    if (entry.getName().equals(name)) {
                        if (result == null) {
                            result = new LinkedHashMap<>(1);
                        }
                        result.put(internalPath, new AbstractMap.SimpleEntry<>(jarInputStream, entry));
                        if (first) {
                            break nextPath;
                        } else {
                            continue nextPath;
                        }
                    }
                }
                jarInputStream.close();
            } catch (IOException e) {
                if (exceptions == null) {
                    exceptions = new ArrayDeque<>(2);
                }
                exceptions.add(e);
            }
        }

        if (result != null) {
            if (scanScope == this.internalPaths) {
                String[] known = result.keySet().toArray(new String[0]);
                if (first) {
                    known = Arrays.copyOf(known, 2);
                }
                knownObjects.put(name, known);
            }

            return result;
        }

        ClassNotFoundException exception = new ClassNotFoundException(name, exceptions != null? exceptions.poll() : null);
        if (exceptions != null && !exceptions.isEmpty()) {
            for (Exception other : exceptions) {
                exception.addSuppressed(other);
            }
        }
        throw exception;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Map.Entry<JarInputStream, JarEntry> entry = lookup(name.replace('.', '/') + ".class", true)
                .entrySet().iterator().next().getValue();
        JarInputStream stream = entry.getKey();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        long size = entry.getValue().getSize();
        byte[] buffer = new byte[size < 0 || size > Integer.MAX_VALUE? 4096 : (int) size];
        BufferedInputStream bis = new BufferedInputStream(stream);
        try {
            int read;
            while ((read = bis.read(buffer)) >= 0) {
                bos.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        try {
            stream.close();
        } catch (IOException ignored) {
        }

        byte[] bytes = bos.toByteArray();
        return defineClass(name, bytes, 0, bytes.length);
    }

    protected URL createJarInJarURL(String jarName, Map.Entry<String, Map.Entry<JarInputStream, JarEntry>> e) {
        String firstTierUrl = Objects.requireNonNull(getClass().getResource(e.getKey()), jarName + " not found").toString();
        URL sendTierUrl;
        try {
            sendTierUrl = new URL(null, "NestedJar:" + firstTierUrl + "!/" + e.getValue().getValue().getName(), new URLStreamHandler() {
                InputStream inputStream;

                @Override
                protected URLConnection openConnection(URL url) throws IOException {
                    URLConnection connection = new URLConnection(url) {
                        @Override
                        public void connect() throws IOException {
                            if (inputStream != null) {
                                inputStream.close();
                            }

                            if (!"NestedJar".equals(url.getProtocol())) {
                                throw new MalformedURLException("Unsupported protocol " + url.getProtocol());
                            }
                            String path = url.getPath();
                            int index = path.lastIndexOf("!/");
                            if (index == -1) {
                                throw new MalformedURLException("Final entry path not found");
                            }
                            URL subJarUrl = new URL(path.substring(0, index));
                            String nestedPath = path.substring(index + 2);
                            InputStream urlStream = subJarUrl.openStream();
                            try {
                                JarInputStream jarInputStream = new JarInputStream(urlStream);
                                JarEntry entry;
                                while ((entry = jarInputStream.getNextJarEntry()) != null) {
                                    if (entry.getName().equals(nestedPath)) {
                                        inputStream = urlStream;
                                        return;
                                    }
                                }
                            } catch (Exception e) {
                                urlStream.close();
                                throw e;
                            }
                        }
                    };

                    connection.connect();
                    return connection;
                }
            });
        } catch (MalformedURLException ex) {
            throw new UncheckedIOException(ex);
        }
        try {
            e.getValue().getKey().close();
        } catch (IOException ignored) {
        }

        return sendTierUrl;
    }

    @Override
    protected URL findResource(String name) {
        try {
            Map.Entry<String, Map.Entry<JarInputStream, JarEntry>> e = lookup(name, false).entrySet().iterator().next();
            return createJarInJarURL(name, e);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        try {
            return Collections.enumeration(
                    lookup(name, false).entrySet().stream()
                            .map(e-> createJarInJarURL(name, e)).collect(Collectors.toList()));
        } catch (ClassNotFoundException ignored) {
            return Collections.emptyEnumeration();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Nullable
    @Override
    public InputStream getResourceAsStream(String name) {
        try {
            return lookup(name, true).values().iterator().next().getKey();
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    public static void main(String[] args) throws IOException {
        Class<?> clazz = SingleJarLauncherOkButSlow.class;
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

        SingleJarLauncherOkButSlow loader = new SingleJarLauncherOkButSlow(libs, null);

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
