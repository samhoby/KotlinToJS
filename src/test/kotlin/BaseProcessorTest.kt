@file:Suppress("ktlint:standard:no-wildcard-imports")

package processor

import com.tschuchort.compiletesting.*
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import java.io.File
import java.io.OutputStream

@OptIn(ExperimentalCompilerApi::class)
abstract class BaseProcessorTest {
    protected fun compile(vararg sources: SourceFile): List<File> {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
                messageOutputStream = OutputStream.nullOutputStream()
                useKsp2()
                symbolProcessorProviders = mutableListOf(WrapperProcessorProvider())
            }
        compilation.compile()
        return compilation.kspSourcesDir
            .walkTopDown()
            .filter { it.isFile }
            .toList()
    }
}
