import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import io.github.samhoby.kotlintojs.tests.BaseProcessorTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneFunctionProcessorTests : BaseProcessorTest() {
    @Test
    fun `should collect a top-level function into JsExportUtils`() {
        val source =
            SourceFile.kotlin(
                "Greeting.kt",
                $$"""
                import io.github.samhoby.kotlintojs.annotations.JsExportFunction

                @JsExportFunction
                fun greet(name: String): String = "Hello, $name"
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "JsExportUtils.kt" }.readText()

        assertTrue(wrapperCode.contains("object JsExportUtils"), "Should generate a JsExportUtils object")
        assertTrue(wrapperCode.contains("fun greet(name: String): String"), "Standalone function should be exported")
        assertTrue(wrapperCode.contains("= greet(name)"), "Should delegate to the original top-level function")
    }

    @Test
    fun `should collect multiple top-level functions from several files into one JsExportUtils`() {
        val first =
            SourceFile.kotlin(
                "First.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportFunction

                @JsExportFunction
                fun alpha(): String = "a"
                """.trimIndent(),
            )
        val second =
            SourceFile.kotlin(
                "Second.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportFunction

                @JsExportFunction
                fun beta(): String = "b"
                """.trimIndent(),
            )

        val files = compile(first, second)
        val utils = files.filter { file -> file.name == "JsExportUtils.kt" }
        assertEquals(utils.size, 1, "All standalone functions must land in a single JsExportUtils file")

        val wrapperCode = utils.single().readText()
        assertTrue(wrapperCode.contains("fun alpha()"), "First standalone function should be present")
        assertTrue(wrapperCode.contains("fun beta()"), "Second standalone function should be present")
    }

    @Test
    fun `should convert collection types in a standalone function`() {
        val source =
            SourceFile.kotlin(
                "Tags.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportFunction

                @JsExportFunction
                fun tags(values: List<String>): List<String> = values
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "JsExportUtils.kt" }.readText()

        assertTrue(wrapperCode.contains("values: Array<String>"), "List parameter should become Array")
        assertTrue(wrapperCode.contains("): Array<String>"), "List return should become Array")
        assertTrue(wrapperCode.contains("tags(values.toList())"), "Should convert the array argument back to a list")
    }

    @Test
    fun `should wrap a suspend standalone function in Promise`() {
        val source =
            SourceFile.kotlin(
                "Loader.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportFunction
                import kotlinx.coroutines.delay

                @JsExportFunction
                suspend fun load(id: String): String {
                    delay(1)
                    return id                                
                } 
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "JsExportUtils.kt" }.readText()

        assertTrue(
            wrapperCode.contains("fun load(id: String): Promise<String>"),
            "Suspend function should return Promise",
        )
        assertTrue(wrapperCode.contains("scope.promise"), "Should delegate through a coroutine scope")
        assertTrue(wrapperCode.contains("load(id)"), "Should call the original suspend function")
    }

    @Test
    fun `should error on overloaded standalone functions without JsName`() {
        val source =
            SourceFile.kotlin(
                "Overloads.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportFunction

                @JsExportFunction
                fun handle(value: String): String = value

                @JsExportFunction
                fun handle(value: Int): Int = value
                """.trimIndent(),
            )

        val messages = compileWithMessages(source)
        assertTrue(
            messages.any { message -> message.contains("name mangling conflict") },
            "Overloaded standalone functions without @JsName should fail the build",
        )
    }
}
