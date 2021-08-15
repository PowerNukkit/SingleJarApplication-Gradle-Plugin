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

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

/**
 * @author joserobjr
 * @since 2021-08-14
 */

public class SingleJarLauncherFailed extends URLClassLoader {
    private SingleJarLauncherFailed(URL[] urls, ClassLoader parent) {
        super(urls, parent, protocol -> new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) throws IOException {
                if (!url.getProtocol().equals("jar")) {
                    // Not a jar
                    return url.openConnection();
                }

                String path = url.getPath();
                int index = path.indexOf("!/");
                int subJarIndex = index > 0? path.indexOf("!/", index + 2) : -1;
                if (index == -1 || subJarIndex == -1) {
                    // Jar is not nested
                    return url.openConnection();
                }

                if (path.indexOf("!/", subJarIndex + 2) != -1) {
                    throw new MalformedURLException("3 deep nested JAR URLs are not supported");
                }

                return new URLConnection(url) {
                    InputStream entryInputStream;
                    { connect(); }

                    @Override
                    public void connect() throws IOException {
                        if (entryInputStream != null) {
                            entryInputStream.close();
                            entryInputStream = null;
                        }

                        int subProtocol = path.indexOf(':', 4);
                        String rootJarPath = path.substring(subProtocol + 1, index);
                        JarFile jarFile;
                        try {
                            jarFile = new JarFile(new File(new URL(path.substring(4, subProtocol), null, -1, rootJarPath).toURI()));
                        } catch (URISyntaxException e) {
                            throw new MalformedURLException("Bad URI Syntax: " + e);
                        }

                        String subJarPath = path.substring(index + 2, subJarIndex);
                        JarEntry subJarEntry = jarFile.getJarEntry(subJarPath);
                        if (subJarEntry == null) {
                            jarFile.close();
                            return;
                        }

                        String lookupName = path.substring(subJarIndex + 2);
                        JarInputStream subJarInputStream = new JarInputStream(jarFile.getInputStream(subJarEntry));
                        JarEntry lookupEntry;
                        while ((lookupEntry = subJarInputStream.getNextJarEntry()) != null) {
                            if (lookupName.equals(lookupEntry.getName())) {
                                entryInputStream = new InputStream() {
                                    final InputStream source = subJarInputStream;
                                    @Override
                                    public int read() throws IOException {
                                        return source.read();
                                    }
                                    @Override
                                    public int read(@NotNull byte[] b) throws IOException {
                                        return source.read(b);
                                    }
                                    @Override
                                    public int read(@NotNull byte[] b, int off, int len) throws IOException {
                                        return source.read(b, off, len);
                                    }
                                    @Override
                                    public long skip(long n) throws IOException {
                                        return source.skip(n);
                                    }
                                    @Override
                                    public int available() throws IOException {
                                        return source.available();
                                    }
                                    @Override
                                    public void close() throws IOException {
                                        try {
                                            source.close();
                                        } finally {
                                            jarFile.close();
                                        }
                                    }
                                    @Override
                                    public void mark(int readlimit) {
                                        source.mark(readlimit);
                                    }
                                    @Override
                                    public void reset() throws IOException {
                                        source.reset();
                                    }
                                    @Override
                                    public boolean markSupported() {
                                        return source.markSupported();
                                    }
                                };
                            }
                        }
                    }

                    @Override
                    public InputStream getInputStream() {
                        return entryInputStream;
                    }
                };
            }
        });
    }

    public static void main(String[] args) throws IOException {
        Class<?> clazz = SingleJarLauncherFailed.class;
        URL jarUrl = clazz.getProtectionDomain().getCodeSource().getLocation();
        URL[] libs;
        try (JarFile jarFile = new JarFile(jarUrl.getFile())) {
            try (Stream<JarEntry> entryStream = jarFile.stream()) {
                libs = entryStream.filter(it -> {
                            System.out.println("Entry: " + it.getName());
                            Path parent = Paths.get(it.getName()).getParent();
                            if (parent == null) {
                                return false;
                            }
                            return parent.equals(Paths.get("META-INF/lib"));
                        })
                        .map(it -> Objects.requireNonNull(clazz.getResource("/" + it.getName()), it.toString()))
                        .toArray(URL[]::new);
            }
        }

        SingleJarLauncherFailed loader = new SingleJarLauncherFailed(libs, null);
        loader.addURL(jarUrl);
        //Thread.currentThread().setContextClassLoader(loader);

        String className = clazz.getName();
        className = className.substring(0, className.length() - 18);

        try {
            Class<?> loadedClass = loader.loadClass(className, true);
            Method main = loadedClass.getDeclaredMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
