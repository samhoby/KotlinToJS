package annotations

/**
 * Marks a single function for JS export wrapper generation.
 *
 * - **Inside a `@JsExportClass`**: the annotated function is included in the class wrapper
 *   alongside all other public functions of that class.
 * - **Inside a non-annotated class**: only the annotated function is added to that class's wrapper.
 * - **Top-level (no enclosing class)**: the function is collected into a shared `JsExportUtils`
 *   object together with all other standalone annotated functions.
 *
 * ```kotlin
 * class OrderService {
 *     @JsExportFunction
 *     fun placeOrder(items: List<String>): Map<String, Int> = TODO()
 * }
 * // generates: object OrderServiceJs { fun placeOrder(items: Array<String>): Json }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class JsExportFunction
