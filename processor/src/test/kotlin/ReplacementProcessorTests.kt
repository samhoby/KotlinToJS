import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import io.github.samhoby.kotlintojs.tests.BaseProcessorTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplacementProcessorTests : BaseProcessorTest() {
    private val replacementDeclarations =
        """
        import io.github.samhoby.kotlintojs.annotations.JsExportClass
        import io.github.samhoby.kotlintojs.annotations.JsExportConverter
        import io.github.samhoby.kotlintojs.annotations.JsExportReplacement
        import kotlin.js.ExperimentalJsExport
        import kotlin.js.JsExport

        sealed class Either<out E, out T> {
            data class Left<E>(val value: E) : Either<E, Nothing>()
            data class Right<T>(val value: T) : Either<Nothing, T>()
        }

        @JsExport
        @OptIn(ExperimentalJsExport::class)
        @JsExportReplacement(replaces = Either::class)
        class JsEither<out E, out T>(
            val success: Boolean,
            val data: T? = null,
            val error: E? = null,
        ) {
            companion object {
                @JsExportConverter
                @JsExport.Ignore
                fun <E, T> fromEither(either: Either<E, T>): JsEither<E, T> = when (either) {
                    is Either.Left -> JsEither(success = false, error = either.value)
                    is Either.Right -> JsEither(success = true, data = either.value)
                }
            }
        }
        """.trimIndent()

    @Test
    fun `should replace a type wrapping an exportable argument`() {
        val source =
            SourceFile.kotlin(
                "UserService.kt",
                """
                $replacementDeclarations

                @JsExportClass
                class UserService {
                    fun getCode(): Either<String, Int> = Either.Right(1)
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapper = files.single { file -> file.name == "UserServiceJs.kt" }.readText()

        assertTrue(wrapper.contains("JsEither<String, Int>"))
        assertTrue(wrapper.contains("JsEither.fromEither(service.getCode())"))
    }

    @Test
    fun `should keep BigInt Long passthrough inside a replacement`() {
        val source =
            SourceFile.kotlin(
                "CountService.kt",
                """
                $replacementDeclarations

                @JsExportClass
                class CountService {
                    fun getCount(): Either<String, Long> = Either.Right(1L)
                }
                """.trimIndent(),
            )

        val files = compileWithOptions(listOf(source), mapOf("longAsBigInt" to "true"))
        val wrapper = files.single { file -> file.name == "CountServiceJs.kt" }.readText()

        assertTrue(wrapper.contains("JsEither<String, Long>"))
    }

    @Test
    fun `should carry a collection type argument raw inside a replacement`() {
        val source =
            SourceFile.kotlin(
                "ListService.kt",
                """
                $replacementDeclarations

                @JsExportClass
                class ListService {
                    fun getItems(): Either<String, List<Int>> = Either.Right(listOf(1))
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapper = files.single { file -> file.name == "ListServiceJs.kt" }.readText()

        // The passthrough converter returns the payload unchanged, so the boundary keeps the raw
        // `List<Int>` (exposed to TypeScript as `KtList<Int>`) rather than converting it to `Array`.
        assertTrue(wrapper.contains("JsEither<String, List<Int>>"))
        assertTrue(wrapper.contains("JsEither.fromEither(service.getItems())"))
    }

    @Test
    fun `should replace a non-generic type with no type arguments`() {
        val source =
            SourceFile.kotlin(
                "StatusService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass
                import io.github.samhoby.kotlintojs.annotations.JsExportConverter
                import io.github.samhoby.kotlintojs.annotations.JsExportReplacement
                import kotlin.js.ExperimentalJsExport
                import kotlin.js.JsExport

                sealed class Status {
                    object Ok : Status()
                    object Fail : Status()
                }

                @JsExport
                @OptIn(ExperimentalJsExport::class)
                @JsExportReplacement(replaces = Status::class)
                class JsStatus(val ok: Boolean) {
                    companion object {
                        @JsExportConverter
                        @JsExport.Ignore
                        fun fromStatus(status: Status): JsStatus = JsStatus(status is Status.Ok)
                    }
                }

                @JsExportClass
                class StatusService {
                    fun current(): Status = Status.Ok
                }
                """.trimIndent(),
            )

        val wrapper = compile(source).single { file -> file.name == "StatusServiceJs.kt" }.readText()

        // Status has no type parameters, so the replacement type is used bare, not parameterized.
        assertTrue(wrapper.contains(": JsStatus"), "The bare replacement type should be used as the return type")
        assertFalse(wrapper.contains("JsStatus<"), "A non-generic replacement must not be parameterized")
        assertTrue(wrapper.contains("JsStatus.fromStatus(service.current())"))
    }

    @Test
    fun `should report a KSP error when a replacement has no converter function`() {
        val source =
            SourceFile.kotlin(
                "BadReplacement.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportReplacement
                import kotlin.js.ExperimentalJsExport
                import kotlin.js.JsExport

                sealed class Either<out E, out T> {
                    data class Left<E>(val value: E) : Either<E, Nothing>()
                    data class Right<T>(val value: T) : Either<Nothing, T>()
                }

                @JsExport
                @OptIn(ExperimentalJsExport::class)
                @JsExportReplacement(replaces = Either::class)
                class JsEither<out E, out T>(val success: Boolean) {
                    companion object
                }
                """.trimIndent(),
            )

        val messages = compileWithMessages(source)

        assertTrue(
            messages.any { message -> message.contains("must declare a companion") },
            "A replacement without a @JsExportConverter function should produce a clear KSP error",
        )
    }
}
