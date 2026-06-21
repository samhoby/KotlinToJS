package processor

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Kotlin/JS name mangling caused by overloaded functions.
 *
 * When Kotlin compiles overloaded functions to JS it produces mangled names like
 * `sum_vux9f0$` and `sum_za3rmp$` which break any stable JS API contract.
 * The processor detects these conflicts and requires `@JsName` on every overload.
 */
class CodeManglingProcessorTests : BaseProcessorTest() {
    // --- Conflict detection ---

    @Test
    fun `should error when @JsExportClass has overloaded functions without @JsName`() {
        val source =
            SourceFile.kotlin(
                "MathService.kt",
                """
                import annotations.JsExportClass

                @JsExportClass
                class MathService {
                    fun sum(a: Int, b: Int): Int = a + b
                    fun sum(a: Double, b: Double): Double = a + b
                }
                """.trimIndent(),
            )

        val messages = compileWithMessages(source)

        assertTrue(
            messages.any { message -> message.contains("name mangling conflict") && message.contains("sum") },
            "Should report a conflict for overloaded 'sum' without @JsName",
        )
    }

    @Test
    fun `should error when @JsExportFunction has overloaded functions without @JsName`() {
        val source =
            SourceFile.kotlin(
                "MathService.kt",
                """
                import annotations.JsExportFunction

                class MathService {
                    @JsExportFunction
                    fun sum(a: Int, b: Int): Int = a + b

                    @JsExportFunction
                    fun sum(a: Double, b: Double): Double = a + b
                }
                """.trimIndent(),
            )

        val messages = compileWithMessages(source)

        assertTrue(
            messages.any { message -> message.contains("name mangling conflict") && message.contains("sum") },
            "Should report a conflict for overloaded @JsExportFunction 'sum' without @JsName",
        )
    }

    @Test
    fun `should error when only some overloads have @JsName`() {
        val source =
            SourceFile.kotlin(
                "MathService.kt",
                """
                import annotations.JsExportClass
                import kotlin.js.JsName

                @JsExportClass
                class MathService {
                    @JsName("sumInt")
                    fun sum(a: Int, b: Int): Int = a + b
                    fun sum(a: Double, b: Double): Double = a + b
                }
                """.trimIndent(),
            )

        val messages = compileWithMessages(source)

        assertTrue(
            messages.any { message -> message.contains("name mangling conflict") && message.contains("sum") },
            "Should report a conflict when only one overload has @JsName",
        )
    }

    @Test
    fun `should error when two @JsName overloads resolve to the same exported name`() {
        val source =
            SourceFile.kotlin(
                "MathService.kt",
                """
                import annotations.JsExportClass
                import kotlin.js.JsName

                @JsExportClass
                class MathService {
                    @JsName("sum")
                    fun sum(a: Int, b: Int): Int = a + b

                    @JsName("sum")
                    fun sum(a: Double, b: Double): Double = a + b
                }
                """.trimIndent(),
            )

        val messages = compileWithMessages(source)

        assertTrue(
            messages.any { message -> message.contains("same JS name") },
            "Should report a duplicate exported name conflict",
        )
    }

    // --- Successful resolution with @JsName ---

    @Test
    fun `should generate distinct wrapper functions when overloads have unique @JsName`() {
        val source =
            SourceFile.kotlin(
                "MathService.kt",
                """
                import annotations.JsExportClass
                import kotlin.js.JsName

                @JsExportClass
                class MathService {
                    @JsName("sumInt")
                    fun sum(a: Int, b: Int): Int = a + b

                    @JsName("sumDouble")
                    fun sum(a: Double, b: Double): Double = a + b
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name =="MathServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("fun sumInt("), "First overload should use @JsName value")
        assertTrue(wrapperCode.contains("fun sumDouble("), "Second overload should use @JsName value")
        assertFalse(wrapperCode.contains("fun sum("), "Original overloaded name should not appear in wrapper")
    }

    @Test
    fun `should resolve @JsExportFunction overloads with @JsName`() {
        val source =
            SourceFile.kotlin(
                "MathService.kt",
                """
                import annotations.JsExportFunction
                import kotlin.js.JsName

                class MathService {
                    @JsName("sumInt")
                    @JsExportFunction
                    fun sum(a: Int, b: Int): Int = a + b

                    @JsName("sumDouble")
                    @JsExportFunction
                    fun sum(a: Double, b: Double): Double = a + b
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name =="MathServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("fun sumInt("), "Int overload should be exported as sumInt")
        assertTrue(wrapperCode.contains("fun sumDouble("), "Double overload should be exported as sumDouble")
    }

    @Test
    fun `should apply type conversions correctly on each named overload`() {
        val source =
            SourceFile.kotlin(
                "MathService.kt",
                """
                import annotations.JsExportClass
                import kotlin.js.JsName

                @JsExportClass
                class MathService {
                    @JsName("sumLong")
                    fun sum(a: Long, b: Long): Long = a + b

                    @JsName("sumList")
                    fun sum(a: List<Int>, b: List<Int>): List<Int> = a + b
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapperCode = files.single { file -> file.name =="MathServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("fun sumLong("), "Long overload should be renamed")
        assertTrue(wrapperCode.contains("a: Long"), "Long parameter should stay Long in BigInt mode")
        assertFalse(wrapperCode.contains("toLong()"), "Should not call toLong() in BigInt mode")
        assertTrue(wrapperCode.contains("fun sumList("), "List overload should be renamed")
        assertTrue(wrapperCode.contains("Array<Int>"), "List parameter should convert to Array")
    }

    @Test
    fun `should apply suspend and type conversions on named overloads`() {
        val source =
            SourceFile.kotlin(
                "MathService.kt",
                """
                import annotations.JsExportClass
                import kotlin.js.JsName
                import kotlinx.coroutines.delay

                @JsExportClass
                class MathService {
                    @JsName("sumSync")
                    fun sum(a: Int, b: Int): Int = a + b

                    @JsName("sumAsync")
                    suspend fun sum(a: Long, b: Long): Long {
                        delay(1)
                        return a + b
                    }
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapperCode = files.single { file -> file.name =="MathServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("fun sumSync("), "Sync overload should be exported as sumSync")
        assertFalse(wrapperCode.contains("Promise<Int>"), "Sync overload should not return Promise")
        assertTrue(wrapperCode.contains("fun sumAsync("), "Async overload should be exported as sumAsync")
        assertTrue(wrapperCode.contains("Promise<Long>"), "Async Long return should become Promise<Long> in BigInt mode")
    }

    // --- @JsName as a rename on a single function (no overload conflict) ---

    @Test
    fun `should use @JsName as the exported name even without overloading`() {
        val source =
            SourceFile.kotlin(
                "GreetService.kt",
                $$"""
            import annotations.JsExportClass
            import kotlin.js.JsName

            @JsExportClass
            class GreetService {
                @JsName("sayHello")
                fun greet(name: String): String = "Hello, $name"
            }

                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name =="GreetServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("fun sayHello("), "Should use @JsName value as the exported function name")
        assertFalse(wrapperCode.contains("fun greet("), "Original Kotlin name should not appear in wrapper")
    }
}
