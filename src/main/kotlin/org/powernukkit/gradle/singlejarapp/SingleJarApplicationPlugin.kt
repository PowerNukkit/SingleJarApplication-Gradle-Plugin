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

package org.powernukkit.gradle.singlejarapp

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.distribution.Distribution
import org.gradle.api.distribution.DistributionContainer
import org.gradle.api.distribution.plugins.DistributionPlugin
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.internal.artifacts.dsl.LazyPublishArtifact
import org.gradle.api.internal.plugins.DefaultArtifactPublicationSet
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.application.CreateStartScripts
import org.gradle.api.tasks.bundling.Jar
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.Callable

/**
 * @author joserobjr
 * @since 2021-08-13
 */
class SingleJarApplicationPlugin: Plugin<Project> {
    companion object {
        const val GENERATE_SINGLE_JAR_LAUNCHER_CLASS_TASK_NAME = "generateSingleJarLauncherClass"
    }

    @Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION")
    private val Project.applicationConvention get() = convention.plugins["application"] as AppConvention
    private val Project.applicationExtension get()= extensions.getByType(JavaApplication::class.java)
    private val Project.launcherClassDir get() = buildDir.resolve("singleJarLauncher")
    private val Project.mainClassName: String get() = requireNotNull(applicationExtension.mainClass.orNull ?: applicationConvention.mainClassName) {
        "application.mainClass was not specified"
    }
    private val Project.outputLauncherClassInternalName get() = mainClassName.replace('.', '/') + "\$SingleJarLauncher"
    private val Project.outputLauncherClassFile: File get() {
        return launcherClassDir.resolve(Paths.get("$outputLauncherClassInternalName.class").toFile())
    }

    override fun apply(project: Project) = with(project) {
        pluginManager.apply(ApplicationPlugin::class.java)
        addGenerateLauncherClassTask()
        extensions.configure(DistributionContainer::class.java) { distributions ->
            distributions.all { dist ->
                val taskName = if (dist.name == DistributionPlugin.MAIN_DISTRIBUTION_NAME) {
                    "distSingleJarApp"
                } else {
                    dist.name + "DistSingleJarApp"
                }
                addArchiveTask(taskName, dist)
            }
        }
    }

    private fun Project.addGenerateLauncherClassTask() {
        tasks.register(GENERATE_SINGLE_JAR_LAUNCHER_CLASS_TASK_NAME) { task ->
            with(task) {
                dependsOn(JavaPlugin.CLASSES_TASK_NAME)
                description = "Generates a class that will setup a ClassLoader to load the bundled jar files inside the single jar application"
                outputs.file(Callable { outputLauncherClassFile })
                doLast {
                    val fromInternalName = Type.getInternalName(SingleJarLauncher::class.java)
                    val finalInternalName = mainClassName.replace('.', '/') + "\$SingleJarLauncher"

                    val mappings = mapOf(
                        fromInternalName to finalInternalName,
                        "$fromInternalName\$1" to "$finalInternalName\$1",
                        "$fromInternalName\$1\$1" to "$finalInternalName\$1\$1",
                        //"$fromInternalName\$1\$1\$1" to "$finalInternalName\$1\$1\$1",
                    )

                    val packageDir = outputLauncherClassFile.parentFile.mkdirsOrFail()
                    packageDir.remapClass("SingleJarLauncher", mappings)
                    packageDir.remapClass("SingleJarLauncher\$1", mappings)
                    packageDir.remapClass("SingleJarLauncher\$1\$1", mappings)
                    //packageDir.remapClass("SingleJarLauncher\$1\$1\$1", mappings)
                }
            }
        }
    }

    private fun File.remapClass(resource: String, mappings: Map<String, String>) {
        val outputClass = resolve(mappings.entries.first { it.key.endsWith(resource) }.value.substringAfterLast('/') + ".class")
        outputClass.writeBytes(remap("$resource.class", mappings))
    }

    private fun remap(resource: String, mappings: Map<String, String>): ByteArray {
        val url = checkNotNull(SingleJarLauncher::class.java.run { getResource(resource) }) { "Resource not found: $resource" }
        val reader = ClassReader(url.readBytes())
        val writer = ClassWriter(0)
        val visitor = ClassRemapper(writer, SimpleRemapper(mappings))
        reader.accept(visitor, ClassReader.EXPAND_FRAMES)
        return writer.toByteArray()
    }

    private fun File.mkdirsOrFail(): File {
        if (isDirectory) {
            return this
        }
        check(mkdirs()) { "Could not create the directory $this" }
        return this
    }

    private fun Project.addArchiveTask(taskName: String, distribution: Distribution) {
        val archiveTask = tasks.register(taskName, Jar::class.java) { task ->
            with(task) {
                description = "Bundles the project as an executable single jar application."
                group = "distribution"
                archiveBaseName.convention(distribution.distributionBaseName)
                archiveClassifier.convention("fatjar")
                destinationDirectory.convention(layout.buildDirectory.dir("distributions"))
                dependsOn(GENERATE_SINGLE_JAR_LAUNCHER_CLASS_TASK_NAME)

                val jar = tasks.named(JavaPlugin.JAR_TASK_NAME).get() as Jar
                val scripts = tasks.named(ApplicationPlugin.TASK_START_SCRIPTS_NAME).get() as CreateStartScripts

                val libs = copySpec()
                libs.into("META-INF")
                libs.with(distribution.contents)
                libs.exclude { it.file.parentFile == scripts.outputDir }
                libs.exclude { it.file == jar.archiveFile.get().asFile }
                libs.includeEmptyDirs = false

                task.filesMatching("**/MANIFEST.MF") { details ->
                    details.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                }

                task.with(libs)
                task.from(Callable {
                    zipTree(jar.archiveFile)
                })
                task.from(launcherClassDir)
                task.fileMode = "755".toInt(8)

                manifest {
                    it.from(jar.manifest)
                    it.attributes["Main-Class"] = "$mainClassName\$SingleJarLauncher"
                }
            }
        }

        val achieveArtifact = LazyPublishArtifact(archiveTask)
        extensions.getByType(DefaultArtifactPublicationSet::class.java).addCandidate(achieveArtifact)
    }
}
