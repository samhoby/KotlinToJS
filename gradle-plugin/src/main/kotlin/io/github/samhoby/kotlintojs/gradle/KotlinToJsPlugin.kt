package io.github.samhoby.kotlintojs.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget

private const val GROUP = "io.github.samhoby"
private const val VERSION = "0.1.1"

/**
 * Gradle plugin that applies KSP and wires up the KotlinToJS annotation processor.
 *
 * Users only need `id("io.github.samhoby.kotlintojs")` in their `plugins { }` block. The plugin:
 * - applies `com.google.devtools.ksp` automatically,
 * - adds the `annotations` artifact to `commonMainImplementation`, and
 * - adds the `processor` artifact to `kspJs`.
 *
 * Requires `org.jetbrains.kotlin.multiplatform` to be applied in the same project.
 */
@Suppress("unused")
class KotlinToJsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            if (!project.plugins.hasPlugin("com.google.devtools.ksp")) {
                project.plugins.apply("com.google.devtools.ksp")
            }

            project.dependencies.add("commonMainImplementation", "$GROUP:annotations:$VERSION")

            project.afterEvaluate {
                // kspJs is created lazily by KSP after the user declares a js() target,
                // so we defer until afterEvaluate when the configuration is guaranteed to exist.
                project.dependencies.add("kspJs", "$GROUP:processor:$VERSION")

                val kmpExtension = project.extensions.findByType(KotlinMultiplatformExtension::class.java)

                // Search the JS target for the BigInt flag
                val hasBigInt =
                    kmpExtension
                        ?.targets
                        ?.filterIsInstance<KotlinJsIrTarget>()
                        ?.any { jsTarget ->
                            val args = jsTarget.compilerOptions.freeCompilerArgs.orNull ?: emptyList()
                            args.contains("-Xes-long-as-bigint")
                        } == true

                // Inject the argument into KSP invisibly
                if (hasBigInt) {
                    project.extensions.configure(KspExtension::class.java) { ksp ->
                        ksp.arg("longAsBigInt", "true")
                    }
                }
            }
        }
    }
}
