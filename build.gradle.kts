plugins {
    java
    application
}

group = "com.jev.probe"
version = "0.2.1"

repositories {
    mavenCentral()
}

dependencies {
    // The SAME org.json API the Android app already uses, so every piece of
    // prompt/response parsing ports over unchanged (Android ships org.json in
    // the platform; on the JVM it is just a dependency).
    implementation("org.json:json:20240303")

    // UI Automation + Win32 from the JVM: enumerating a chat window's control
    // tree (the desktop equivalent of Android's AccessibilityService), window
    // geometry, and screen capture for the OCR fallback.
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // OCR fallback, for clients whose control tree cannot be read. Added with
    // the reader that needs it (see capture/).
    // implementation("net.sourceforge.tess4j:tess4j:5.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// -------------------------------------------------------------- runtime flags
//
// ONE definition for every way the APP can start.
//
// There is deliberately no `-Dfile.encoding=UTF-8` here, and adding one back is
// what broke the tray's right-click menu. Measured on this machine (JDK 17,
// zh-CN Windows), rendering the same native AWT menu three ways:
//
//     platform default ........ 「显示面板」 renders correctly
//     -Dfile.encoding=GBK ..... byte-identical to the above
//     -Dfile.encoding=UTF-8 ... 「显示面板」 comes out as ▯▯▯▯ (tofu boxes)
//
// The mechanism is in the JDK: `sun.awt.windows.WMenuItemPeer`'s static
// initialiser takes the native menu font from `sun.awt.windows.awtLocalization`
// (both the default and the zh_CN bundle say "SansSerif-plain-11") and hands
// that family name to GDI. Overriding `file.encoding` moves the charset the font
// configuration resolves that logical family against, GDI ends up creating a
// Latin-only face, and CJK has no glyphs. Native menus are drawn by Windows, so
// no amount of Java-side font work reaches them.
//
// The charset is therefore NOT this app's to pin. Nothing needed it to be:
// every file read/write, the HTTP layer and the JNA calls all name UTF-8 (or
// UTF-16) explicitly, and the compiler below is pinned to UTF-8, so the
// platform default is exactly what both the console and GDI expect.
//
// Also deliberately absent: `sun.stdout.encoding` / `sun.stderr.encoding`.
// Measured: they do not affect AWT rendering either way, and pinning stdout to
// UTF-8 is what turns a Chinese log line into mojibake in a GBK console. The
// app prints ASCII only (see the first line of Main.main). `sun.jnu.encoding`
// is never touched — it controls how path bytes reach the OS, and forcing UTF-8
// on a GBK system is a known way to break file names outright.
//
// What IS set here is the graphics pipeline flag, whose full story is in
// Main.main. `Main` sets it too, so `gradlew run` is covered either way; passing
// it at JVM start as well means the packaged .exe has it before any Java of
// ours runs.
val runtimeArgs = listOf(
    "-Dsun.java2d.twp.forcesw=true",
)

application {
    mainClass = "com.jev.probe.desktop.Main"
    applicationDefaultJvmArgs = runtimeArgs
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        // The smoke test prints the conversation it just read off the screen —
        // that output IS the result, so it has to reach the console.
        showStandardStreams = true
    }
    // ONLY the test JVM pins a charset, and only so the smoke test's Chinese
    // output survives the trip through the Gradle daemon: the daemon decodes
    // whatever the test JVM writes using ITS OWN default charset, and the two
    // disagree here. This is about reading a log — no test shows a native menu,
    // so it cannot reintroduce the tofu-boxes bug described above. It has to be
    // a JVM argument rather than `systemProperty`: `file.encoding` is consumed
    // while the charset machinery initialises, which is before any test code —
    // and therefore any system property set from here — ever runs.
    jvmArgs("-Dfile.encoding=UTF-8")
}

// A single runnable fat jar, so the app can be launched by double-clicking
// instead of from a terminal.
tasks.register<Jar>("fatJar") {
    archiveClassifier = "all"
    manifest { attributes["Main-Class"] = "com.jev.probe.desktop.Main" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}

// ------------------------------------------------------------------- app icon
//
// The icon's source of truth is the Java2D drawing in tools/IconGenerator.java
// (this project has no image toolchain on purpose), so regenerating the assets
// is a build task rather than a manual export. Output goes to `assets/` next to
// this file, which is also where jpackage picks the .ico up from.
val iconFile = layout.projectDirectory.file("assets/icon.ico")

val genIcon by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Render the app icon: assets/icon-*.png and the multi-size assets/icon.ico."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.jev.probe.desktop.tools.IconGenerator"
    // So the generator's default `assets` path resolves next to this build file
    // no matter where Gradle was invoked from.
    workingDir = projectDir
}

// ------------------------------------------------------- Windows exe (jpackage)
//
// Produces a folder holding a launcher .exe next to a bundled JRE, so the app
// runs on a machine with no Java installed. `--type app-image` deliberately
// needs no WiX; only an installer (.msi / setup .exe) does, and a folder the
// user can move anywhere is the more useful shape for a personal tool.
val jpackageInputDir = layout.buildDirectory.dir("jpackage-input")
val jpackageDestDir = layout.buildDirectory.dir("jpackage")

val stageJpackageInput by tasks.registering(Sync::class) {
    description = "Stage the app jar plus its runtime dependencies for jpackage."
    from(tasks.named("jar"))
    from(configurations.runtimeClasspath)
    into(jpackageInputDir)
}

tasks.register<Exec>("packageExe") {
    group = "distribution"
    description = "Build a self-contained Windows folder: AIChatAssistant.exe + bundled JRE."
    dependsOn(stageJpackageInput)

    val javaHome = File(System.getProperty("java.home"))
    val jpackage = File(javaHome, "bin/jpackage.exe")
    doFirst {
        require(jpackage.exists()) {
            "找不到 jpackage：${jpackage.absolutePath}（需要 JDK 14+；当前 java.home=$javaHome）"
        }
        // jpackage refuses to write into an app-image directory that already
        // exists, so a second `packageExe` fails with
        // 「应用程序目标目录 … 已存在」. Clear it here rather than making the user
        // delete it by hand before every rebuild.
        delete(jpackageDestDir)
        // The launcher .exe gets the app icon only if jpackage is handed one; the
        // file is generated by `genIcon`, so it may legitimately be absent on a
        // clean checkout — skip the flag rather than failing the packaging.
        if (iconFile.asFile.exists()) {
            args("--icon", iconFile.asFile.absolutePath)
        } else {
            logger.lifecycle("未找到 ${iconFile.asFile}，打包将使用默认图标（可运行 genIcon 生成）")
        }
    }
    executable = jpackage.absolutePath
    args(
        "--type", "app-image",
        "--input", jpackageInputDir.get().asFile.absolutePath,
        "--dest", jpackageDestDir.get().asFile.absolutePath,
        "--main-jar", "aichat-desktop-${project.version}.jar",
        "--main-class", "com.jev.probe.desktop.Main",
        "--name", "AIChatAssistant",
        "--app-version", project.version.toString(),
        "--vendor", "AIChat"
    )
    // The same runtime flags the dev `run` task gets, for the same reasons. One
    // option per `--java-options`: the flag takes a single argument, so the list
    // cannot be flattened into it.
    runtimeArgs.forEach { args("--java-options", it) }
}
