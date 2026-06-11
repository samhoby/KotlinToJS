package processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP entry point that instantiates [WrapperProcessor] for each compilation round.
 *
 * Registered via `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
 * so that KSP discovers it automatically when the plugin is on the classpath.
 */
class WrapperProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        WrapperProcessor(environment.codeGenerator, environment.logger, environment.options)
}
