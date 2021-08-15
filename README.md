Single Jar Application Gradle Plugin
-----------------------------------

This gradle plugin let you create fat-jars without repacking the libs, they are kept intact inside the lib folder of the single jar.

The classes are loaded by a custom class loaded that is added to the resulting jar
which is capable of loading and providing resources of jars that are inside the same jar.

## Usage

Add the code snipped bellow in your `build.gradle` or `build.gradle.kts` file.

The file JAR file will be generated when you `build` the project or execute the new
`distSingleJarApp` task.

The single jar application file will be located at the `build/distributions` folder by default.

Important note: The jar which is created in `build/libs` folder is left untouched, 
it's not the same jar as the one inside the `build/distributions` folder.

If you open the jar with a ZIP file viewer such as 7-Zip you will be able to find all
the dependencies inside the `META-INF/lib` folder and a new synthetic class named:
`your.package.YourMainClass$SingleJarLauncher` (it uses your main class name as base).

### Kotlin DSL
```kt
plugins {
    id("org.powernukkit.single-jar-application") version "0.1.0-SNAPSHOT"
}

application {
    mainClass.set("your.package.YourMainClass")
}
```

### Groovy DSL
```groovy
plugins {
    id 'org.powernukkit.single-jar-application' version '0.1.0-SNAPSHOT'
}

application {
    mainClass = 'your.package.YourMainClass'
}
```
