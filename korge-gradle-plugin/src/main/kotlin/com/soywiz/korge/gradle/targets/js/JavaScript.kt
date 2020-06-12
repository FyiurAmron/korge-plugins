package com.soywiz.korge.gradle.targets.js

import com.moowork.gradle.node.*
import com.moowork.gradle.node.exec.*
import com.moowork.gradle.node.npm.*
import com.moowork.gradle.node.task.*
import com.soywiz.kds.mapWhile
import com.soywiz.korge.gradle.*
import com.soywiz.korge.gradle.targets.*
import com.soywiz.korge.gradle.util.*
import groovy.text.*
import org.gradle.*
import org.gradle.api.*
import org.gradle.api.artifacts.repositories.*
import org.gradle.api.file.*
import org.gradle.process.*
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.tasks.*
import java.io.*
import java.lang.management.*

val Project.node_modules get() = korgeCacheDir["node_modules"]
val Project.webMinFolder get() = buildDir["web-min"]
val Project.webFolder get() = buildDir["web"]
val Project.webMinWebpackFolder get() = buildDir["web-min-webpack"]
val Project.mocha_node_modules get() = buildDir["node_modules"]

fun Project.nodeExec(vararg args: Any, workingDir: File? = null): ExecResult = NodeExecRunner(this).apply {
	this.workingDir = workingDir ?: this.workingDir
	this.environment += mapOf(
		"NODE_PATH" to node_modules
	)
	arguments = args.toList()
}.execute()

internal var _webServer: DecoratedHttpServer? = null

fun Project.configureJavaScript() {
	plugins.apply("kotlin-dce-js")

	gkotlin.apply {
		js {
			this.attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.js)
			compilations.all {
				it.kotlinOptions.apply {
					languageVersion = "1.3"
					sourceMap = true
					metaInfo = true
					moduleKind = "umd"
					suppressWarnings = korge.supressWarnings
				}
			}

			browser {
				testTask {
					useKarma {
						useChromeHeadless()
					}
				}
			}
		}
	}

	project.korge.addDependency("jsMainImplementation", "org.jetbrains.kotlin:kotlin-stdlib-js")
	project.korge.addDependency("jsTestImplementation", "org.jetbrains.kotlin:kotlin-test-js")

	configureNode()
	addWeb()
}

private fun Project.configureNode() {
	plugins.apply("com.moowork.node")

	(project["node"] as NodeExtension).apply {
		this.version = "10.14.2"
		//this.version = "8.11.4"
		this.download = true

		this.workDir = korgeCacheDir["nodejs"]
		this.npmWorkDir = korgeCacheDir["npm"]
		this.yarnWorkDir = korgeCacheDir["yarn"]
		this.nodeModulesDir = node_modules

		//this.nodeModulesDir = java.io.File(project.buildDir, "npm")
	}

	// Fix for https://github.com/srs/gradle-node-plugin/issues/301
	repositories.whenObjectAdded {
		if (it is IvyArtifactRepository) {
			it.metadataSources {
				it.artifact()
			}
		}
	}

	val jsInstallMocha = project.addTask<NpmTask>("jsInstallMocha") { task ->
		task.onlyIf { !node_modules["/mocha"].exists() }
		task.setArgs(listOf("install", "mocha@5.2.0"))
	}

	val jsInstallCanvas = project.addTask<NpmTask>("jsInstallCanvas") { task ->
		task.onlyIf { !node_modules["/canvas"].exists() }
		task.setArgs(listOf("install", "canvas@2.2.0"))
	}

	val jsInstallWebpack = project.addTask<NpmTask>("jsInstallWebpack") { task ->
		task.onlyIf { !node_modules["webpack"].exists() || !node_modules["webpack-cli"].exists() }
		task.setArgs(listOf("install", "webpack@4.28.2", "webpack-cli@3.1.2"))
	}

	val jsInstallMochaHeadlessChrome = project.addTask<NpmTask>("jsInstallMochaHeadlessChrome") { task ->
		task.onlyIf { !node_modules["mocha-headless-chrome"].exists() }
		task.setArgs(listOf("install", "mocha-headless-chrome@2.0.1"))
	}

	val jsCompilations = project["kotlin"]["targets"]["js"]["compilations"]

	val populateNodeModules = project.addTask<DefaultTask>("populateNodeModules") { task ->
		task.doLast {
			copy { copy ->
				copy.from(jsCompilations["main"]["output"]["allOutputs"])
				copy.from(jsCompilations["test"]["output"]["allOutputs"])
				(jsCompilations["test"]["runtimeDependencyFiles"] as Iterable<File>).forEach { file ->
					if (file.exists() && !file.isDirectory()) {
						copy.from(zipTree(file.absolutePath).matching { it.include("*.js") })
					}
				}
				for (sourceSet in project.gkotlin.sourceSets) {
					copy.from(sourceSet.resources)
				}
				copy.into(mocha_node_modules)
			}
			copy { copy ->
				copy.from("$node_modules/mocha")
				copy.into("$mocha_node_modules/mocha")
			}
		}
	}

	val jsTestChrome = project.addTask<NodeTask>("jsTestChrome", dependsOn = listOf(jsCompilations["test"]["compileKotlinTaskName"], jsInstallMochaHeadlessChrome, jsInstallMocha, populateNodeModules)) { task ->
		task.doFirst {
			File("$buildDir/node_modules/tests.html").writeText("""<!DOCTYPE html><html>
                    <head>
                        <title>Mocha Tests</title>
                        <meta charset="utf-8">
                        <link rel="stylesheet" href="mocha/mocha.css">
                        <script src="https://cdnjs.cloudflare.com/ajax/libs/require.js/2.3.6/require.min.js"></script>
                    </head>
                    <body>
                    <div id="mocha"></div>
                    <script src="mocha/mocha.js"></script>
                    <script>
                        requirejs.config({'baseUrl': '.', 'paths': { 'tests': '../classes/kotlin/js/test/${project.name}_test' }});
                        mocha.setup('bdd');
                        require(['tests'], function() { mocha.run(); });
                    </script>
                    </body>
                    </html>
                """)
		}
		task.setScript(node_modules["mocha-headless-chrome/bin/start"])
		task.setArgs(listOf("-f", "$buildDir/node_modules/tests.html", "-a", "no-sandbox", "-a", "disable-setuid-sandbox", "-a", "allow-file-access-from-files"))
	}

	val runMocha = project.addTask<NodeTask>("runMocha", dependsOn = listOf(
		jsCompilations["test"]["compileKotlinTaskName"],
		jsInstallMocha, jsInstallCanvas,
		populateNodeModules
	)) { task ->
		task.setEnvironment(mapOf("NODE_MODULES" to "$node_modules${File.pathSeparator}$mocha_node_modules"))
		task.setScript(node_modules["mocha/bin/mocha"])
		task.setWorkingDir(project.file("$buildDir/node_modules"))
		task.setArgs(listOf("--timeout", "15000", "${project.name}_test.js"))
	}

	// Only run JS tests if not in windows
	if (!org.apache.tools.ant.taskdefs.condition.Os.isFamily(org.apache.tools.ant.taskdefs.condition.Os.FAMILY_WINDOWS)) {
		project.tasks.getByName("jsTest")?.dependsOn(runMocha)
	}
}

private fun Project.addWeb() {
	fun configureJsWeb(task: JsWebCopy, minimized: Boolean) {
		val excludesNormal = arrayOf("**/*.kotlin_metadata","**/*.kotlin_module","**/*.MF","**/*.kjsm","**/*.map","**/*.meta.js")
		val excludesJs = arrayOf("**/*.js")
		val excludesAll = excludesNormal + excludesJs

		fun CopySpec.configureWeb() {
			if (minimized) {
				//include("**/require.min.js")
				exclude(*excludesAll)
			} else {
				exclude(*excludesNormal)
			}
		}

		task.targetDir = project.buildDir[if (minimized) "web-min" else "web"]

        val kotlinTargets by lazy { project["kotlin"]["targets"] }
        val jsCompilations by lazy { kotlinTargets["js"]["compilations"] }
		val mainJsCompilation = (jsCompilations["main"] as KotlinJsCompilation)

		val kotlinJsCompile = tasks.getByName("compileKotlinJs") as Kotlin2JsCompile


        //project.afterEvaluate {
        //task.exclude(*excludesNormal)
        task.doLast {
            copy {
                it.apply {
                    includeEmptyDirs = false

                    if (minimized) {
                        from((project["runDceJsKotlin"] as KotlinJsDce).destinationDir) { copy -> copy.exclude(*excludesNormal) }
                    }
                    from(mainJsCompilation.output.allOutputs) { copy -> copy.configureWeb() }
                    from("${project.buildDir}/npm/node_modules") { copy -> copy.configureWeb() }
                    for (file in (jsCompilations["test"]["runtimeDependencyFiles"] as FileCollection).toList()) {
                        if (file.exists() && !file.isDirectory) {
                            from(project.zipTree(file.absolutePath)) { copy -> copy.configureWeb() }
                            from(project.zipTree(file.absolutePath)) { copy -> copy.include("**/*.min.js") }
                        } else {
                            from(file) { copy -> copy.configureWeb() }
                            from(file) { copy -> copy.include("**/*.min.js") }
                        }
                    }

                    for (target in listOf(kotlinTargets["js"], kotlinTargets["metadata"])) {
                        val main = (target["compilations"]["main"] as KotlinCompilation<*>)
                        for (sourceSet in main.kotlinSourceSets) {
                            from(sourceSet.resources) { copy -> copy.configureWeb() }
                        }
                    }
                    into(task.targetDir)
                }
            }

			val indexHtmlTemplateFile = "index.v2.template.html"
			val requireMinJsTemplateFile = "require.min.v2.template.js"
			val indexTemplateHtml = when {
				task.targetDir[indexHtmlTemplateFile].exists() -> task.targetDir[indexHtmlTemplateFile].readText()
				else -> getResourceBytes(indexHtmlTemplateFile).toString(Charsets.UTF_8)
			}
			// If a custom version of require.min.js
			val requireMinTemplateJs = when {
				task.targetDir[requireMinJsTemplateFile].exists() -> task.targetDir[requireMinJsTemplateFile].readText()
				else -> getResourceBytes(requireMinJsTemplateFile).toString(Charsets.UTF_8)
			}

			/*
  function isInheritanceFromInterface(ctor, iface) {
    if (ctor === iface)
      return true;
    var metadata = ctor.$metadata$;
    if (metadata != null) {
      var interfaces = metadata.interfaces;
      for (var i = 0; i < interfaces.length; i++) {
        if (isInheritanceFromInterface(interfaces[i], iface)) {
          return true;
        }}
    }var superPrototype = ctor.prototype != null ? Object.getPrototypeOf(ctor.prototype) : null;
    var superConstructor = superPrototype != null ? superPrototype.constructor : null;
    return superConstructor != null && isInheritanceFromInterface(superConstructor, iface);
  }
			 */

            task.targetDir["kotlin.js"].writeText(applyPatchesToKotlinRuntime(task.targetDir["kotlin.js"].readText()))
            task.targetDir["index.html"].writeText(
                SimpleTemplateEngine().createTemplate(indexTemplateHtml).make(mapOf(
                    "OUTPUT" to kotlinJsCompile.outputFile.nameWithoutExtension,
                    "TITLE" to korge.name
                )).toString())

			task.targetDir["require.min.js"].writeText(requireMinTemplateJs)
			task.targetDir["favicon.png"].writeBytes(korge.getIconBytes(16))
			task.targetDir["appicon.png"].writeBytes(korge.getIconBytes(180)) // https://developer.apple.com/library/archive/documentation/AppleApplications/Reference/SafariWebContent/ConfiguringWebApplications/ConfiguringWebApplications.html
        }
	}

	val jsWeb = project.addTask<JsWebCopy>(name = "jsWeb", dependsOn = listOf("jsJar", "jsMainClasses")) { task ->
		task.group = GROUP_KORGE_PACKAGE
		configureJsWeb(task, minimized = false)
	}

	val jsWebMin = project.addTask<JsWebCopy>(name = "jsWebMin", dependsOn = listOf("runDceJsKotlin")) { task ->
		task.group = GROUP_KORGE_PACKAGE
		configureJsWeb(task, minimized = true)
	}

	val jsStopWeb = project.addTask<Task>(name = "jsStopWeb") { task ->
		task.doLast {
			println("jsStopWeb: ${ManagementFactory.getRuntimeMXBean().name}-${Thread.currentThread()}")
			_webServer?.server?.stop(0)
			_webServer = null
		}
	}

	fun runServer(blocking: Boolean) {
		if (_webServer == null) {
			val address = korge.webBindAddress
			val port = korge.webBindPort
			val server = staticHttpServer(project.buildDir["web"], address = address, port = port)
			_webServer = server
			try {
				val openAddress = when (address) {
					"0.0.0.0" -> "127.0.0.1"
					else -> address
				}
				openBrowser("http://$openAddress:${server.port}/index.html")
				if (blocking) {
					while (true) {
						Thread.sleep(1000L)
					}
				}
			} finally {
				if (blocking) {
					server.server.stop(0)
					_webServer = null
				}
			}
		}
		_webServer?.updateVersion?.incrementAndGet()
	}

	val jsWebRun = project.tasks.create<Task>("jsWebRun") {
		dependsOn(jsWeb)
		doLast {
			runServer(!project.gradle.startParameter.isContinuous)
		}
	}

	val jsWebRunNonBlocking = project.tasks.create<Task>("jsWebRunNonBlocking") {
		dependsOn(jsWeb)
		doLast {
			runServer(false)
		}
	}

	project.gradle.addBuildListener(object : BuildAdapter() {
	})
	// In continuous mode this is executed everytime. We need to detect the "Build cancelled." event.
	project.gradle.buildFinished {
		//println("project.gradle.buildFinished!!")
		//_webServer?.server?.stop(0)
		//_webServer = null
	}

	val runJs = project.tasks.create<Task>("runJs") {
		group = GROUP_KORGE_RUN
		dependsOn(jsWebRun)
	}

	val runJsNonBlocking = project.tasks.create<Task>("runJsNonBlocking") {
		group = GROUP_KORGE_RUN
		dependsOn(jsWebRunNonBlocking)
	}

	val jsWebMinWebpack = project.addTask<DefaultTask>("jsWebMinWebpack", dependsOn = listOf(
		"jsInstallWebpack",
		"jsWebMin"
	)) { task ->
		task.group = GROUP_KORGE_PACKAGE
		task.doLast {
			copy { copy ->
				copy.from(webMinFolder)
				copy.into(webMinWebpackFolder)
				copy.exclude("**/*.js", "**/index.template.html", "**/index.html")
			}

			val webpackConfigJs = buildDir["webpack.config.js"]

			fun getProjectAscendants(project: Project): List<Project> {
				val projects = arrayListOf<Project>()
				var cproject: Project? = project
				while (cproject != null) {
					projects += cproject
					cproject = cproject?.parent
				}
				return projects
			}

			// @TODO: This is a hack
			val fullJsName = getProjectAscendants(project).reversed().map { it.name }.joinToString("-")
			webpackConfigJs.writeText("""
                    const path = require('path');
                    const webpack = require('webpack');
                    const modules = ${webMinFolder.absolutePath.quoted};

                    module.exports = {
                      context: modules,
                      entry: ${"$webMinFolder/${fullJsName}.js".quoted},
                      resolve: {
                        modules: [ modules ],
                      },
                      output: {
                        path: ${webMinWebpackFolder.absolutePath.quoted},
                        filename: 'bundle.js'
                      },
                      target: 'node',
                      plugins: [
                        new webpack.IgnorePlugin(/^canvas${'$'}/)
                      ]
                    };
                """.trimIndent())

			nodeExec(node_modules["webpack/bin/webpack.js"], "--config", webpackConfigJs)

			val indexHtml = webMinFolder["index.html"].readText()
			webMinWebpackFolder["index.html"].writeText(indexHtml.replace(Regex("<script data-main=\"(.*?)\" src=\"require.min.js\" type=\"text/javascript\"></script>"), "<script src=\"bundle.js\" type=\"text/javascript\"></script>"))
		}
	}
}

fun applyPatchToKotlinRuntime(runtime: String, patchSrc: String, patchDst: String): String {
	val srcRegex = Regex(patchSrc
		.trim()
		.replace("\\", "\\\\")
		.replace(".", "\\.")
		.replace("?", "\\?")
		.replace("[", "\\[")
		.replace("(", "\\(")
		.replace("{", "\\{")
		.replace(")", "\\)")
		.replace("}", "\\}")
		.replace("]", "\\]")
		.replace("+", "\\+")
		.replace("*", "\\*")
		.replace("\$", "\\\$")
		.replace("^", "\\^")
		.replace(Regex("\\s+", RegexOption.MULTILINE), "\\\\s*")
		, setOf(
			RegexOption.MULTILINE, RegexOption.IGNORE_CASE
		))
	//println(srcRegex.pattern)
	return runtime.replace(srcRegex) { result ->
		val srcLines = result.value.lines().size
		val dstLines = patchDst.lines().size
		if (srcLines > dstLines) {
			patchDst + "\n".repeat(srcLines - dstLines)
		} else {
			println("WARNING: More lines in patch than expected")
			patchDst
		}
	}
}

fun applyPatchesToKotlinRuntime(runtime: String): String {
	val (src, dst) = getResourceString("/patches/isInheritanceFromInterface.kotlin.js.patch").split("--------------------------------")
	return applyPatchToKotlinRuntime(runtime, src, dst)
}
