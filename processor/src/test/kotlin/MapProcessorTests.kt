import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import io.github.samhoby.kotlintojs.tests.BaseProcessorTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for `MapHandler`'s numeric-ID conversion functions, recursive nested-map registration,
 * primitive key conversion, and the conditional import of generated conversion functions.
 *
 * Conversion functions are named `toMapN` / `toJsonN` where N is the order of first encounter
 * within a processing round. A single-map source always produces ID 1; a two-map source produces
 * IDs 1 and 2 in declaration order.
 */
class MapProcessorTests : BaseProcessorTest() {
    @Test
    fun `should convert Int map keys with toInt`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<Int, String>): Map<Int, String> = payload
                }
                """.trimIndent(),
            )

        val files = compile(source)
        val wrapperCode = files.single { file -> file.name == "CollectionServiceJs.kt" }.readText()
        val conversionsCode = files.single { file -> file.name == "TypeConversion.kt" }.readText()

        assertTrue(wrapperCode.contains("payload.toMap1()"))
        assertTrue(conversionsCode.contains("fun Json.toMap1()"))
        assertTrue(
            conversionsCode.contains("k.toInt()"),
            "Int keys must be parsed from the JS string key via k.toInt()",
        )
    }

    @Test
    fun `should convert Double map keys with toDouble`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<Double, String>): Map<Double, String> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()

        assertTrue(conversionsCode.contains("fun Json.toMap1()"))
        assertTrue(conversionsCode.contains("k.toDouble()"), "Double keys must be parsed via k.toDouble()")
    }

    @Test
    fun `should convert Boolean map keys with toBoolean`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<Boolean, String>): Map<Boolean, String> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()

        assertTrue(conversionsCode.contains("fun Json.toMap1()"))
        assertTrue(conversionsCode.contains("k.toBoolean()"), "Boolean keys must be parsed via k.toBoolean()")
    }

    @Test
    fun `should convert Long map keys with toLong in default mode`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<Long, String>): Map<Long, String> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()

        assertTrue(conversionsCode.contains("fun Json.toMap1()"))
        assertTrue(conversionsCode.contains("k.toLong()"), "Long keys must be parsed via k.toLong()")
    }

    @Test
    fun `should report a KSP error for an unsupported map key type`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                data class CompositeKey(val a: String, val b: Int)

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<CompositeKey, String>): Map<CompositeKey, String> = payload
                }
                """.trimIndent(),
            )

        val messages = compileWithMessages(source)

        assertTrue(
            messages.any { message -> message.contains("Unsupported @JsExport map key type") },
            "A non-primitive map key should produce a clear KSP error",
        )
    }

    @Test
    fun `should register an inner map nested inside a list value`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun getData(): Map<String, List<Map<String, Int>>> = mapOf("a" to listOf(mapOf("b" to 1)))
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()

        assertTrue(
            conversionsCode.contains("fun Json.toMap1()"),
            "Should register the outer map (ID 1)",
        )
        assertTrue(
            conversionsCode.contains("fun Json.toMap2()"),
            "Should recursively register the inner map nested inside the list element (ID 2)",
        )
    }

    @Test
    fun `should generate distinct conversion names for two maps in one service`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun a(): Map<String, Int> = emptyMap()
                    fun b(): Map<Int, String> = emptyMap()
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()

        assertTrue(conversionsCode.contains("fun Json.toMap1()"), "First map should receive ID 1")
        assertTrue(
            conversionsCode.contains("fun Json.toMap2()"),
            "Second map should receive ID 2 so the two decode functions do not clash",
        )
    }

    @Test
    fun `should import only the referenced map conversion function`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(config: Map<String, String>) = config
                }
                """.trimIndent(),
            )

        val wrapperCode = compile(source).single { file -> file.name == "CollectionServiceJs.kt" }.readText()

        assertTrue(
            wrapperCode.contains("import kotlintojs.generated.toMap1"),
            "Wrapper should import the decode function it references",
        )
    }

    @Test
    fun `should not import the generated package when no map is present`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun tags(): List<String> = listOf("a", "b")
                }
                """.trimIndent(),
            )

        val wrapperCode = compile(source).single { file -> file.name == "CollectionServiceJs.kt" }.readText()

        assertFalse(
            wrapperCode.contains("kotlintojs.generated"),
            "A wrapper with no map boundary must not import from the possibly absent generated package",
        )
    }

    @Test
    fun `should convert Short map keys with toShort`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<Short, String>): Map<Short, String> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()
        assertTrue(conversionsCode.contains("k.toShort()"), "Short keys must be parsed via k.toShort()")
    }

    @Test
    fun `should convert Byte map keys with toByte`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<Byte, String>): Map<Byte, String> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()
        assertTrue(conversionsCode.contains("k.toByte()"), "Byte keys must be parsed via k.toByte()")
    }

    @Test
    fun `should convert Float map keys with toFloat`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<Float, String>): Map<Float, String> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()
        assertTrue(conversionsCode.contains("k.toFloat()"), "Float keys must be parsed via k.toFloat()")
    }

    @Test
    fun `should cast Double map values with unsafeCast`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<String, Double>): Map<String, Double> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()
        assertTrue(conversionsCode.contains("unsafeCast<Double>"), "Double values must be cast via unsafeCast<Double>")
    }

    @Test
    fun `should cast Float map values with unsafeCast`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<String, Float>): Map<String, Float> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()
        assertTrue(conversionsCode.contains("unsafeCast<Float>"), "Float values must be cast via unsafeCast<Float>")
    }

    @Test
    fun `should cast Short map values with unsafeCast`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<String, Short>): Map<String, Short> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()
        assertTrue(conversionsCode.contains("unsafeCast<Short>"), "Short values must be cast via unsafeCast<Short>")
    }

    @Test
    fun `should cast Byte map values with unsafeCast`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<String, Byte>): Map<String, Byte> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()
        assertTrue(conversionsCode.contains("unsafeCast<Byte>"), "Byte values must be cast via unsafeCast<Byte>")
    }

    @Test
    fun `should cast Boolean map values with unsafeCast`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<String, Boolean>): Map<String, Boolean> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()
        assertTrue(conversionsCode.contains("unsafeCast<Boolean>"), "Boolean values must be cast via unsafeCast<Boolean>")
    }

    @Test
    fun `should cast nullable map values with a nullable unsafeCast`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<String, Int?>): Map<String, Int?> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()
        assertTrue(
            conversionsCode.contains("unsafeCast<Int?>"),
            "A nullable value type must keep the nullability marker in the unsafeCast target",
        )
    }

    @Test
    fun `should cast a non-primitive map value to its qualified type`() {
        val source =
            SourceFile.kotlin(
                "CollectionService.kt",
                """
                import io.github.samhoby.kotlintojs.annotations.JsExportClass

                data class Payload(val label: String)

                @JsExportClass
                class CollectionService {
                    fun process(payload: Map<String, Payload>): Map<String, Payload> = payload
                }
                """.trimIndent(),
            )

        val conversionsCode = compile(source).single { file -> file.name == "TypeConversion.kt" }.readText()
        assertTrue(
            conversionsCode.contains("unsafeCast<Payload>"),
            "A non-primitive value falls through to the else branch and is cast to its qualified name",
        )
    }
}
