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

plugins {
    id("com.gradle.plugin-publish") version "0.15.0"
    `java-gradle-plugin`
    kotlin("jvm") version "1.5.10"
}

val kotlinVersion = "1.5.10"

gradlePlugin {
    plugins {
        create("single-jar-application") {
            id = "org.powernukkit.single-jar-application"
            implementationClass = "org.powernukkit.gradle.singlejarapp.SingleJarApplicationPlugin"
        }
    }
}

pluginBundle {
    website = "https://devs.powernukkit.org/"
    vcsUrl = "https://github.com/PowerNukkit/SingleJarApplication-Gradle-Plugin"
    description = "This gradle plugin let you create fatjars without repacking the libs, they are kept intact inside the lib folder of the single jar!"
    tags = listOf("fatjar", "shadow", "single-jar", "packaging", "jar", "jar-in-jar")

    (plugins) {
        "single-jar-application" {
            displayName = "Single Jar Application"
        }
    }
}

group = "org.powernukkit"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib", kotlinVersion))
    implementation(kotlin("reflect", kotlinVersion))
    implementation("org.ow2.asm", "asm", "9.2")
    implementation("org.ow2.asm", "asm-commons", "9.2")
}
