package processor

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValueClassProcessorTests : BaseProcessorTest() {
    @Test
    fun `should unwrap value class parameter to its underlying type`() {
        val source =
            SourceFile.kotlin(
                "UserService.kt",
                $$"""
                import annotations.JsExportClass

                @JvmInline
                value class UserId(val value: String)

                @JsExportClass
                class UserService {
                    fun getUser(id: UserId): String = "user-${id.value}"
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "UserServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("id: String"), "Value class parameter should be unwrapped to String")
        assertTrue(wrapperCode.contains("UserId(id)"), "Wrapper should reconstruct the value class before calling service")
        assertFalse(wrapperCode.contains("id: UserId"), "Original value class type should not appear at the boundary")
    }

    @Test
    fun `should unwrap value class return type to its underlying type`() {
        val source =
            SourceFile.kotlin(
                "UserService.kt",
                """
                import annotations.JsExportClass

                @JvmInline
                value class UserId(val value: String)

                @JsExportClass
                class UserService {
                    fun createUser(): UserId = UserId("123")
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "UserServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("): String"), "Value class return should be unwrapped to String")
        assertTrue(wrapperCode.contains(".value"), "Wrapper should access the underlying property of the return value")
        assertFalse(wrapperCode.contains("): UserId"), "Original value class return type should not appear at the boundary")
    }

    @Test
    fun `should unwrap value class in both parameter and return positions`() {
        val source =
            SourceFile.kotlin(
                "OrderService.kt",
                """
                import annotations.JsExportClass

                @JvmInline
                value class OrderId(val id: String)

                @JsExportClass
                class OrderService {
                    fun mirror(order: OrderId): OrderId = order
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "OrderServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("order: String"), "Parameter should be unwrapped to String")
        assertTrue(wrapperCode.contains("OrderId(order)"), "Value class should be reconstructed for the service call")
        assertTrue(wrapperCode.contains(".id"), "Return should be unwrapped via the underlying property")
        assertTrue(wrapperCode.contains("): String"), "Return type should be String")
    }

    @Test
    fun `should apply inner Long conversion transitively through value class in BigInt mode`() {
        val source =
            SourceFile.kotlin(
                "InvoiceService.kt",
                """
                import annotations.JsExportClass

                @JvmInline
                value class InvoiceId(val id: Long)

                @JsExportClass
                class InvoiceService {
                    fun getInvoice(id: InvoiceId): InvoiceId = id
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapperCode = files.single { file -> file.name == "InvoiceServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("id: Long"), "Long-backed value class parameter should expose Long in BigInt mode")
        assertTrue(wrapperCode.contains("InvoiceId(id)"), "Should reconstruct the value class")
        assertTrue(wrapperCode.contains(".id"), "Should access the underlying Long property")
    }

    @Test
    fun `should handle multiple distinct value class types in one wrapper`() {
        val source =
            SourceFile.kotlin(
                "ShopService.kt",
                """
                import annotations.JsExportClass

                @JvmInline value class ProductId(val value: String)
                @JvmInline value class Price(val cents: Int)

                @JsExportClass
                class ShopService {
                    fun getPrice(id: ProductId): Price = Price(100)
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "ShopServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("id: String"), "ProductId parameter should unwrap to String")
        assertTrue(wrapperCode.contains("ProductId(id)"), "Should reconstruct ProductId")
        assertTrue(wrapperCode.contains("): Int"), "Price return should unwrap to Int")
        assertTrue(wrapperCode.contains(".cents"), "Should access Price.cents")
    }

    @Test
    fun `should generate static methods when @JsExportClass is on the value class itself`() {
        val source =
            SourceFile.kotlin(
                "UserId.kt",
                $$"""
                import annotations.JsExportClass

                @JvmInline
                @JsExportClass
                value class UserId(val value: String) {
                    fun display(): String = "User: $value"
                    fun withPrefix(prefix: String): String = "$prefix-$value"
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "UserIdJs.kt" }.readText()

        assertTrue(wrapperCode.contains("fun display(userId: String)"), "Self param 'userId' should be first argument")
        assertTrue(wrapperCode.contains("fun withPrefix(userId: String, prefix: String)"), "Self param precedes function params")
        assertTrue(wrapperCode.contains("UserId(userId).display()"), "Should construct value class and call method")
        assertTrue(wrapperCode.contains("UserId(userId).withPrefix(prefix)"), "Should construct value class for method with args")
        assertFalse(wrapperCode.contains("val service"), "No service property for value class wrappers")
    }

    @Test
    fun `should unwrap value class return type when @JsExportClass is on the value class itself`() {
        val source =
            SourceFile.kotlin(
                "Score.kt",
                """
                import annotations.JsExportClass

                @JvmInline
                @JsExportClass
                value class Score(val points: Int) {
                    fun doubled(): Score = Score(points * 2)
                    fun asString(): String = points.toString()
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "ScoreJs.kt" }.readText()

        assertTrue(wrapperCode.contains("fun doubled(score: Int): Int"), "Self-returning method should expose underlying type")
        assertTrue(wrapperCode.contains("Score(score).doubled()"), "Should construct value class for the call")
        assertTrue(wrapperCode.contains(".points"), "Should unwrap the Score return value via .points")
        assertTrue(wrapperCode.contains("fun asString(score: Int): String"), "Non-value return type passes through normally")
    }

    @Test
    fun `should wrap suspend method of @JsExportClass value class in Promise`() {
        val source =
            SourceFile.kotlin(
                "TaskId.kt",
                $$"""
                import annotations.JsExportClass
                import kotlinx.coroutines.delay

                @JvmInline
                @JsExportClass
                value class TaskId(
                    val id: String,
                ) {
                    suspend fun fetch(): String {
                        delay(1)
                        return "fetched-$id"
                    }
                }

                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "TaskIdJs.kt" }.readText()

        assertTrue(wrapperCode.contains("fun fetch(taskId: String): Promise<String>"), "Suspend method should return Promise")
        assertTrue(wrapperCode.contains("TaskId(taskId).fetch()"), "Should construct value class in suspend body")
        assertFalse(wrapperCode.contains("val service"), "No service property for value class wrappers")
    }

    @Test
    fun `should use Long as self param type for Long-backed value class in BigInt mode`() {
        val source =
            SourceFile.kotlin(
                "EventId.kt",
                $$"""
                import annotations.JsExportClass

                @JvmInline
                @JsExportClass
                value class EventId(val id: Long) {
                    fun label(): String = "event-$id"
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapperCode = files.single { file -> file.name == "EventIdJs.kt" }.readText()

        assertTrue(
            wrapperCode.contains("fun label(eventId: Long): String"),
            "Long-backed value class should use Long self param in BigInt mode",
        )
        assertTrue(wrapperCode.contains("EventId(eventId).label()"), "Should construct Long-backed value class")
    }

    @Test
    fun `should unwrap value class on @JsExportFunction`() {
        val source =
            SourceFile.kotlin(
                "TokenService.kt",
                """
                import annotations.JsExportFunction

                @JvmInline
                value class Token(val raw: String)

                class TokenService {
                    @JsExportFunction
                    fun validate(token: Token): Boolean = token.raw.isNotEmpty()
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "TokenServiceJs.kt" }.readText()
        assertTrue(wrapperCode.contains("token: String"), "Value class on @JsExportFunction should unwrap to String")
        assertTrue(wrapperCode.contains("Token(token)"), "Should reconstruct Token for the service call")
    }
}
