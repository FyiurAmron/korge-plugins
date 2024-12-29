plugins {
	java
	`java-gradle-plugin`
	kotlin("jvm")
	`maven-publish`
    id("com.gradle.plugin-publish") version "1.3.0"
}

gradlePlugin {
    website = "https://korge.org/"
    vcsUrl = "https://github.com/korlibs/korge-plugins"

	plugins {
		create("korge") {
			id = "com.soywiz.korge"
			displayName = "Korge"
			description = "Multiplatform Game Engine for Kotlin"
			implementationClass = "com.soywiz.korge.gradle.KorgeGradlePlugin"
            tags = listOf("korge", "game", "engine", "game engine", "multiplatform", "kotlin")
		}
/*
        create("korge-android") {
            id = "com.soywiz.korge.android"
            displayName = "KorgeAndroid"
            description = "Multiplatform Game Engine for Kotlin with integrated android support"
            implementationClass = "com.soywiz.korge.gradle.KorgeWithAndroidGradlePlugin"
        }
 */
	}
}

//val kotlinVersion: String by project
val androidBuildGradleVersion: String by project

dependencies {
    //implementation(project(":korge-build"))
    implementation(kotlin("gradle-plugin"))
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    //testImplementation("junit:junit:4.12")

    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin")
    implementation("net.sf.proguard:proguard-gradle:6.2.2")
    implementation("com.android.tools.build:gradle:$androidBuildGradleVersion")

    implementation(gradleApi())
    implementation(localGroovy())
}
