package processor

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsExportFunctionProcessorTests : BaseProcessorTest() {
    @Test
    fun `should generate wrapper for non-annotated class when it contains @JsExportFunction`() {
        val source =
            SourceFile.kotlin(
                "OrderService.kt",
                """
                import annotations.JsExportFunction

                class OrderService {
                    @JsExportFunction
                    fun placeOrder(id: String): String = id
                }

                """.trimIndent(),
            )

        val files = compile(source)
        assertTrue(files.any { it.name == "OrderServiceJs.kt" }, "Should generate wrapper for non-annotated class")
    }

    @Test
    fun `should only include annotated functions when class is not @JsExportClass`() {
        val source =
            SourceFile.kotlin(
                "OrderService.kt",
                """
                import annotations.JsExportFunction

                class OrderService {
                    @JsExportFunction
                    fun exportedOp(): String = "exported"

                    fun internalOp(): String = "internal"
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "OrderServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("fun exportedOp()"), "Annotated function should be in wrapper")
        assertFalse(wrapperCode.contains("fun internalOp()"), "Non-annotated function should be excluded")
    }

    @Test
    fun `should collect multiple @JsExportFunction from the same non-annotated class into one wrapper`() {
        val source =
            SourceFile.kotlin(
                "OrderService.kt",
                """
                import annotations.JsExportFunction

                class OrderService {
                    @JsExportFunction
                    fun placeOrder(id: String): String = id

                    @JsExportFunction
                    fun cancelOrder(id: String): Boolean = true

                    fun internalHelper(): Unit = Unit
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperFiles = files.filter { it.name == "OrderServiceJs.kt" }
        assertEquals(1, wrapperFiles.size, "Should produce exactly one wrapper file")

        val wrapperCode = wrapperFiles.first().readText()
        assertTrue(wrapperCode.contains("fun placeOrder("), "First annotated function should be present")
        assertTrue(wrapperCode.contains("fun cancelOrder("), "Second annotated function should be present")
        assertFalse(wrapperCode.contains("fun internalHelper("), "Non-annotated function should be excluded")
    }

    @Test
    fun `should expose Long directly on @JsExportFunction in BigInt mode`() {
        val source =
            SourceFile.kotlin(
                "PaymentService.kt",
                """
                import annotations.JsExportFunction

                class PaymentService {
                    @JsExportFunction
                    fun processPayment(amount: Long): Long = amount
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapperCode = files.single { it.name == "PaymentServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("amount: Long"), "Long parameter should stay Long in BigInt mode")
        assertFalse(wrapperCode.contains("toLong()"), "Should not call toLong() in BigInt mode")
        assertFalse(wrapperCode.contains("toDouble()"), "Should not call toDouble() in BigInt mode")
    }

    @Test
    fun `should apply List conversion on @JsExportFunction`() {
        val source =
            SourceFile.kotlin(
                "CatalogService.kt",
                """
                import annotations.JsExportFunction

                class CatalogService {
                    @JsExportFunction
                    fun getItems(): List<String> = listOf("a", "b")
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "CatalogServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Array<String>"), "List return should become Array")
        assertTrue(wrapperCode.contains("toTypedArray()"), "Should call toTypedArray()")
    }

    @Test
    fun `should apply Map conversion on @JsExportFunction and generate TypeConversion`() {
        val source =
            SourceFile.kotlin(
                "ConfigService.kt",
                """
                import annotations.JsExportFunction

                class ConfigService {
                    @JsExportFunction
                    fun getConfig(): Map<String, String> = mapOf("key" to "value")
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "ConfigServiceJs.kt" }.readText()
        val typeConversion = files.find { it.name == "TypeConversion.kt" }

        assertTrue(wrapperCode.contains("Json"), "Map return should become Json")
        assertTrue(typeConversion != null, "Should generate TypeConversion.kt for Map types")
    }

    @Test
    fun `should wrap suspend @JsExportFunction in Promise`() {
        val source =
            SourceFile.kotlin(
                "NotificationService.kt",
                """
                import annotations.JsExportFunction
                import kotlinx.coroutines.delay

                class NotificationService {
                    @JsExportFunction
                    suspend fun send(message: String): Boolean {
                        delay(1)
                        return true
                    }
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "NotificationServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Promise<Boolean>"), "Suspend return should be wrapped in Promise")
        assertTrue(wrapperCode.contains("scope.promise"), "Should delegate to scope.promise")
        assertTrue(wrapperCode.contains("private val scope: CoroutineScope = MainScope()"), "Should add scope property")
    }

    @Test
    fun `should not duplicate function when @JsExportFunction is inside @JsExportClass`() {
        val source =
            SourceFile.kotlin(
                "UserService.kt",
                """
                import annotations.JsExportClass
                import annotations.JsExportFunction

                @JsExportClass
                class UserService {
                    @JsExportFunction
                    fun getUser(id: String): String = id

                    fun listUsers(): List<String> = listOf("alice")
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { it.name == "UserServiceJs.kt" }.readText()

        val occurrences = wrapperCode.split("fun getUser(").size - 1
        assertEquals(1, occurrences, "getUser should appear exactly once in the wrapper")
        assertTrue(wrapperCode.contains("fun listUsers()"), "Non-annotated function should still be included via @JsExportClass")
    }

    @Test
    fun `should generate separate wrappers for @JsExportFunction in different non-annotated classes`() {
        val source =
            SourceFile.kotlin(
                "Services.kt",
                """
                import annotations.JsExportFunction

                class ServiceA {
                    @JsExportFunction
                    fun opA(): String = "a"
                }

                class ServiceB {
                    @JsExportFunction
                    fun opB(): Int = 1
                }
                """.trimIndent(),
            )

        val files = compile(source)

        assertTrue(files.any { it.name == "ServiceAJs.kt" }, "Should generate wrapper for ServiceA")
        assertTrue(files.any { it.name == "ServiceBJs.kt" }, "Should generate wrapper for ServiceB")

        val wrapperA = files.single { it.name == "ServiceAJs.kt" }.readText()
        val wrapperB = files.single { it.name == "ServiceBJs.kt" }.readText()

        assertTrue(wrapperA.contains("fun opA()") && !wrapperA.contains("fun opB()"), "ServiceAJs should only contain opA")
        assertTrue(wrapperB.contains("fun opB()") && !wrapperB.contains("fun opA()"), "ServiceBJs should only contain opB")
    }

    @Test
    fun `should apply all type conversions together on a single @JsExportFunction in BigInt mode`() {
        val source =
            SourceFile.kotlin(
                "ReportService.kt",
                """
                import annotations.JsExportFunction
                import kotlinx.coroutines.delay

                class ReportService {
                    @JsExportFunction
                    suspend fun buildReport(ids: List<Long>): Map<String, Long> {
                        delay(1)
                        return ids.associate { it.toString() to it }
                    }
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapperCode = files.single { it.name == "ReportServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("ids: Array<Long>"), "List<Long> parameter should become Array<Long> in BigInt mode")
        assertTrue(wrapperCode.contains("Promise<Json>"), "suspend Map return should become Promise<Json>")
        assertTrue(wrapperCode.contains("scope.promise"), "Should use scope.promise")
        assertTrue(files.any { it.name == "TypeConversion.kt" }, "Should generate TypeConversion for Map")
    }
}
