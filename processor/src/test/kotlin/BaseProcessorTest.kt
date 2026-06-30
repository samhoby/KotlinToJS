@file:Suppress("ktlint:standard:no-wildcard-imports")

package io.github.samhoby.kotlintojs.tests

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.*
import io.github.samhoby.kotlintojs.processor.WrapperProcessor
import io.github.samhoby.kotlintojs.processor.WrapperProcessorProvider
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File
import java.io.OutputStream

@OptIn(ExperimentalCompilerApi::class)
abstract class BaseProcessorTest {
    private class OptionsProvider(
        private val opts: Map<String, String>,
    ) : SymbolProcessorProvider {
        override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
            WrapperProcessor(
                environment.codeGenerator,
                environment.logger,
                opts,
            )
    }

    protected fun compile(vararg sources: SourceFile): List<File> = compileWithOptions(sources.toList())

    /**
     * Compiles [sources] with the given KSP processor [options] and returns generated files.
     * Use this when a test needs to configure plugin behaviour (e.g. `"longAsBigInt" to "true"`).
     */
    protected fun compileWithOptions(
        sources: List<SourceFile>,
        options: Map<String, String> = emptyMap(),
    ): List<File> {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources
                inheritClassPath = true
                messageOutputStream = OutputStream.nullOutputStream()
                useKsp2()
                symbolProcessorProviders = mutableListOf(OptionsProvider(options))
            }
        compilation.compile()
        return compilation.kspSourcesDir
            .walkTopDown()
            .filter { file -> file.isFile }
            .toList()
    }

    /**
     * Compiles [sources] and returns all KSP diagnostic messages (errors, warnings) emitted
     * during processing. Use this when a test needs to assert on processor error output rather
     * than on generated files.
     */
    protected fun compileWithMessages(vararg sources: SourceFile): List<String> {
        val messages = mutableListOf<String>()
        val stream =
            object : OutputStream() {
                private val buffer = StringBuilder()

                override fun write(b: Int) {
                    buffer.append(b.toChar())
                }

                override fun flush() {
                    if (buffer.isNotEmpty()) {
                        messages.add(buffer.toString())
                        buffer.clear()
                    }
                }
            }
        KotlinCompilation()
            .apply {
                this.sources = sources.toList()
                inheritClassPath = true
                messageOutputStream = stream
                useKsp2()
                symbolProcessorProviders =
                    mutableListOf(
                        WrapperProcessorProvider(),
                    )
            }.compile()
        stream.flush()
        return messages
    }
}
