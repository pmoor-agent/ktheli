plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The physics module must stay pure: no rendering, no I/O devices, no wall clock.
// Only the Kotlin stdlib is allowed as a runtime dependency.

/**
 * Fails the build if physics sources use anything that could break bit-for-bit reproducibility.
 * java.lang.Math transcendentals may differ by CPU/JIT; StrictMath (via DMath) is specified exactly.
 */
val checkDeterminism = tasks.register("checkDeterminism") {
    group = "verification"
    description = "Scans physics sources for non-deterministic APIs."
    val sources = fileTree("src/main/kotlin")
    val root = projectDir
    inputs.files(sources)
    doLast {
        val transcendental = "sin|cos|tan|asin|acos|atan|atan2|sinh|cosh|tanh|asinh|acosh|atanh|exp|expm1|ln|ln1p|log|log10|log2|pow|cbrt|hypot"
        val forbidden = listOf(
            Regex("""import\s+kotlin\.math\.\*""") to "wildcard kotlin.math import (use DMath)",
            Regex("""import\s+kotlin\.math\.($transcendental)\b""") to "kotlin.math transcendental (use DMath)",
            Regex("""(?<!Strict)\bMath\.($transcendental|random)\b""") to "java.lang.Math transcendental (use DMath)",
            Regex("""\.pow\(""") to "Double.pow (use DMath.pow)",
            Regex("""System\.(currentTimeMillis|nanoTime)""") to "wall clock",
            Regex("""\b(java\.util\.Random|ThreadLocalRandom|SecureRandom|kotlin\.random)\b""") to "unseeded/unspecified RNG (use Pcg32)",
            Regex("""\b(Thread|Executors|coroutines)\b""") to "concurrency",
        )
        val violations = sources.files.flatMap { file ->
            if (file.name == "DMath.kt") emptyList()
            else file.readLines().withIndex().flatMap { (i, line) ->
                forbidden.filter { (re, _) -> re.containsMatchIn(line) }
                    .map { (_, why) -> "${file.relativeTo(root)}:${i + 1}: $why\n    ${line.trim()}" }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Determinism check failed:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.named("check") { dependsOn(checkDeterminism) }
