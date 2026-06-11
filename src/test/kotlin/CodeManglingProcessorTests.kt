package processor

import com.tschuchort.compiletesting.SourceFile
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class CodeManglingProcessorTests : BaseProcessorTest() {

    @Test
    fun `should process functions with JsName annotation`() {
        val source = SourceFile.kotlin("ManglingService.kt", """
            import annotations.JsExportClass
            import kotlin.js.JsName
            
            @JsExportClass
            class ManglingService {
                @JsName("getUserData")
                fun get_user_data(): String = "data"
            }
        """.trimIndent())

        val files = compile(source)
        val wrapperCode = files.single { it.name == "ManglingServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("ManglingService"), "Should reference service class")
        assertTrue(wrapperCode.contains("String"), "Should preserve return type")
    }

    @Test
    fun `should handle JsName with Long conversion`() {
        val source = SourceFile.kotlin("ManglingService.kt", """
            import annotations.JsExportClass
            import kotlin.js.JsName
            
            @JsExportClass
            class ManglingService {
                @JsName("getId")
                fun get_id(userId: Long): Long = userId
            }
        """.trimIndent())

        val files = compile(source)
        val wrapperCode = files.single { it.name == "ManglingServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Double"), "Long should convert to Double")
        assertTrue(wrapperCode.contains("toLong()"), "Should have toLong conversion")
        assertTrue(wrapperCode.contains("toDouble()"), "Should have toDouble conversion")
    }

    @Test
    fun `should handle JsName with List collections`() {
        val source = SourceFile.kotlin("ManglingService.kt", """
            import annotations.JsExportClass
            import kotlin.js.JsName
            
            @JsExportClass
            class ManglingService {
                @JsName("getItems")
                fun get_items(): List<String> = listOf("a", "b")
                
                @JsName("processItems")
                fun process_items(items: List<String>): List<String> = items
            }
        """.trimIndent())

        val files = compile(source)
        val wrapperCode = files.single { it.name == "ManglingServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Array<String>"), "List should convert to Array")
        assertTrue(wrapperCode.contains("toTypedArray()"), "Should convert list to typed array")
        assertTrue(wrapperCode.contains("toList()"), "Should convert array back to list")
    }

    @Test
    fun `should handle JsName with Map and generate conversions`() {
        val source = SourceFile.kotlin("ManglingService.kt", """
            import annotations.JsExportClass
            import kotlin.js.JsName
            
            @JsExportClass
            class ManglingService {
                @JsName("getSettings")
                fun get_settings(): Map<String, String> = mapOf("key" to "value")
            }
        """.trimIndent())

        val files = compile(source)
        val wrapperCode = files.single { it.name == "ManglingServiceJs.kt" }.readText()
        val conversionsCode = files.find { it.name == "TypeConversion.kt" }

        assertTrue(wrapperCode.contains("Json"), "Map should convert to Json")
        assertTrue(conversionsCode != null, "Should generate TypeConversion file")
    }

    @Test
    fun `should mix JsName and regular function annotations`() {
        val source = SourceFile.kotlin("ManglingService.kt", """
            import annotations.JsExportClass
            import kotlin.js.JsName
            
            @JsExportClass
            class ManglingService {
                @JsName("userData")
                fun get_user_data(): String = "data"
                
                fun normalFunction(): String = "normal"
                
                @JsName("processConfig")
                fun process_config(config: String): String = config
            }
        """.trimIndent())

        val files = compile(source)
        val wrapperCode = files.single { it.name == "ManglingServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("String"), "Should preserve types for all functions")
        assertTrue(wrapperCode.contains("ManglingService"), "Should reference service")
    }

    @Test
    fun `should handle JsName on suspend functions with Long return`() {
        val source = SourceFile.kotlin("ManglingService.kt", """
            import annotations.JsExportClass
            import kotlin.js.JsName
            
            @JsExportClass
            class ManglingService {
                @JsName("fetchId")
                suspend fun fetch_id(): Long {
                    kotlinx.coroutines.delay(1)
                    return 1L
                }
            }
        """.trimIndent())

        val files = compile(source)
        val wrapperCode = files.single { it.name == "ManglingServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Promise"), "Suspend should return Promise")
        assertTrue(wrapperCode.contains("Double"), "Long return should convert to Double")
        assertTrue(wrapperCode.contains("scope.promise"), "Should use scope.promise")
    }

    @Test
    fun `should handle JsName with multiple map types`() {
        val source = SourceFile.kotlin("ManglingService.kt", """
            import annotations.JsExportClass
            import kotlin.js.JsName
            
            @JsExportClass
            class ManglingService {
                @JsName("config1")
                fun get_config_1(): Map<Long, String> = mapOf(1L to "value")
                
                @JsName("config2")
                fun get_config_2(): Map<String, Long> = mapOf("key" to 2L)
            }
        """.trimIndent())

        val files = compile(source)
        val wrapperCode = files.single { it.name == "ManglingServiceJs.kt" }.readText()
        val conversionsCode = files.single { it.name == "TypeConversion.kt" }.readText()

        assertTrue(wrapperCode.contains("Json"), "Maps should convert to Json")
        assertTrue(wrapperCode.contains("toJson1()"), "Should reference first conversion")
        assertTrue(wrapperCode.contains("toJson2()"), "Should reference second conversion")
        assertTrue(conversionsCode.contains("toMap1()"), "Should define toMap1")
        assertTrue(conversionsCode.contains("toMap2()"), "Should define toMap2")
    }

    @Test
    fun `should handle JsName with Set of Longs`() {
        val source = SourceFile.kotlin("ManglingService.kt", """
            import annotations.JsExportClass
            import kotlin.js.JsName
            
            @JsExportClass
            class ManglingService {
                @JsName("updateIds")
                fun update_ids(ids: Set<Long>): Set<Long> = ids
            }
        """.trimIndent())

        val files = compile(source)
        val wrapperCode = files.single { it.name == "ManglingServiceJs.kt" }.readText()

        assertTrue(wrapperCode.contains("Array<Double>"), "Set<Long> should convert to Array<Double>")
        assertTrue(wrapperCode.contains("toSet()"), "Should convert back to set")
        assertTrue(wrapperCode.contains("toTypedArray()"), "Should convert Kotlin collection to JS array")
    }

    @Test
    fun `should handle JsName with complex nested types`() {
        val source = SourceFile.kotlin("ManglingService.kt", """
            import annotations.JsExportClass
            import kotlin.js.JsName
            
            @JsExportClass
            class ManglingService {
                @JsName("complex")
                fun handle_complex(
                    data: Map<String, List<Long>>
                ): Map<String, List<Long>> = data
            }
        """.trimIndent())

        val files = compile(source)
        val wrapperCode = files.single { it.name == "ManglingServiceJs.kt" }.readText()
        val conversionsCode = files.find { it.name == "TypeConversion.kt" }

        assertTrue(wrapperCode.contains("Json"), "Nested map should convert to Json")
        assertTrue(conversionsCode != null, "Should generate conversion for complex types")
    }
}
