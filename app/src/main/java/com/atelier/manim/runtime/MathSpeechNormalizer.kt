package com.atelier.manim.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File

class MathSpeechNormalizer(context: Context) {
    private val glossaryFile = File(context.filesDir, "speech_glossary.json")
    @Volatile private var cachedGlossary: Map<String, String>? = null

    private val defaultGlossary = linkedMapOf(
        "Euler" to "Oiler",
        "Euler's" to "Oiler's",
        "Manim" to "Man-im",
        "Fourier" to "For-ee-ay",
        "Cauchy" to "Ko-shee",
        "Riemann" to "Ree-mahn",
        "Laplace" to "Lah-plahs",
        "Dirichlet" to "Dee-ree-klay",
        "Lebesgue" to "Luh-beg",
        "Galois" to "Gal-wah",
        "Eigenvalue" to "Eye-gen-value",
        "eigenvalue" to "eye-gen-value",
        "eigenvector" to "eye-gen-vector",
        "Q.E.D." to "Q E D"
    )

    fun getGlossary(): Map<String, String> {
        cachedGlossary?.let { return it }
        val merged = LinkedHashMap(defaultGlossary)
        if (glossaryFile.exists()) {
            runCatching {
                val obj = JSONObject(glossaryFile.readText(Charsets.UTF_8))
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    merged[k] = obj.optString(k, "")
                }
            }
        }
        cachedGlossary = merged
        return merged
    }

    fun setGlossaryEntries(entries: Map<String, String>) {
        val cur = LinkedHashMap(getGlossary())
        for ((k, v) in entries) {
            val ck = k.trim()
            if (ck.isEmpty()) continue
            if (v.isBlank()) cur.remove(ck) else cur[ck] = v.trim()
        }
        val obj = JSONObject()
        cur.forEach { (k, v) -> obj.put(k, v) }
        runCatching { glossaryFile.writeText(obj.toString(2), Charsets.UTF_8) }
        cachedGlossary = cur
    }

    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""

        // Strip LaTeX math delimiters cleanly
        s = s.replace("$$", " ").replace("$", " ").replace("\\(", " ").replace("\\)", " ")
            .replace("\\[", " ").replace("\\]", " ")

        // Fractions: \frac{a}{b} -> a over b
        val fracRx = Regex("""\\(?:d|t)?frac\{([^{}]+)\}\{([^{}]+)\}""")
        repeat(3) { s = fracRx.replace(s) { m -> "${m.groupValues[1]} over ${m.groupValues[2]}" } }

        // Roots
        s = Regex("""\\sqrt\[([^\]]+)\]\{([^{}]+)\}""").replace(s) { m -> "${m.groupValues[1]}th root of ${m.groupValues[2]}" }
        s = Regex("""\\sqrt\{([^{}]+)\}""").replace(s) { m -> "the square root of ${m.groupValues[1]}" }

        // Common iconic identities & notation
        s = s.replace("e^{i\\pi}", "e to the i pi")
            .replace("e^{i\\theta}", "e to the i theta")
            .replace("\\infty", " infinity ")
            .replace("\\int", " the integral ")
            .replace("\\sum", " the sum ")
            .replace("\\prod", " the product ")
            .replace("\\lim", " the limit ")
            .replace("\\partial", " partial ")
            .replace("\\nabla", " del ")
            .replace("\\forall", " for all ")
            .replace("\\exists", " there exists ")
            .replace("\\in", " in ")
            .replace("\\notin", " not in ")
            .replace("\\subset", " subset of ")
            .replace("\\subseteq", " subset or equal to ")
            .replace("\\cup", " union ")
            .replace("\\cap", " intersection ")
            .replace("\\implies", " implies ")
            .replace("\\iff", " if and only if ")
            .replace("\\to", " goes to ")
            .replace("\\rightarrow", " goes to ")
            .replace("\\approx", " is approximately ")
            .replace("\\neq", " is not equal to ")
            .replace("\\leq", " is less than or equal to ")
            .replace("\\geq", " is greater than or equal to ")
            .replace("\\pm", " plus or minus ")
            .replace("\\times", " times ")
            .replace("\\cdot", " times ")
            .replace("\\div", " divided by ")

        // Greek letters
        val greek = listOf(
            "\\alpha" to "alpha", "\\beta" to "beta", "\\gamma" to "gamma", "\\delta" to "delta",
            "\\epsilon" to "epsilon", "\\varepsilon" to "epsilon", "\\zeta" to "zeta", "\\eta" to "eta",
            "\\theta" to "theta", "\\lambda" to "lambda", "\\mu" to "mu", "\\nu" to "nu",
            "\\xi" to "ksee", "\\pi" to "pi", "\\rho" to "rho", "\\sigma" to "sigma",
            "\\tau" to "tau", "\\phi" to "phi", "\\varphi" to "phi", "\\chi" to "kai",
            "\\psi" to "psi", "\\omega" to "omega", "\\Delta" to "delta", "\\Sigma" to "sigma",
            "\\Omega" to "omega", "\\Gamma" to "gamma", "\\Lambda" to "lambda", "\\Phi" to "phi", "\\Psi" to "psi"
        )
        for ((k, v) in greek) s = s.replace(k, " $v ")

        // Functions & blackboard bold
        s = s.replace("\\mathbb{R}", " real numbers ")
            .replace("\\mathbb{C}", " complex numbers ")
            .replace("\\mathbb{Z}", " integers ")
            .replace("\\mathbb{N}", " natural numbers ")
            .replace("\\mathbb{Q}", " rational numbers ")
            .replace("\\sin", " sine ").replace("\\cos", " cosine ").replace("\\tan", " tangent ")
            .replace("\\ln", " natural log ").replace("\\log", " log ").replace("\\exp", " exponential of ")

        // Powers & Big-O & Unicode math symbols
        s = Regex("""O\(([^)]+)\)""").replace(s) { m -> "big O of ${m.groupValues[1]}" }
        s = Regex("""([A-Za-z0-9])\^2\b""").replace(s) { m -> "${m.groupValues[1]} squared" }
        s = Regex("""([A-Za-z0-9])\^3\b""").replace(s) { m -> "${m.groupValues[1]} cubed" }
        s = Regex("""([A-Za-z0-9])\^\{([^{}]+)\}""").replace(s) { m -> "${m.groupValues[1]} to the power of ${m.groupValues[2]}" }
        s = Regex("""([A-Za-z0-9])\^([A-Za-z0-9-]+)""").replace(s) { m -> "${m.groupValues[1]} to the ${m.groupValues[2]}" }
        s = Regex("""([A-Za-z])_\{([^{}]+)\}""").replace(s) { m -> "${m.groupValues[1]} sub ${m.groupValues[2]}" }
        s = Regex("""([A-Za-z])_([A-Za-z0-9])""").replace(s) { m -> "${m.groupValues[1]} sub ${m.groupValues[2]}" }
        s = Regex("""\b([a-zA-Z])\(([a-zA-Z0-9]+)\)""").replace(s) { m -> "${m.groupValues[1]} of ${m.groupValues[2]}" }

        val unicodeMap = mapOf(
            "π" to " pi ", "θ" to " theta ", "∞" to " infinity ", "≈" to " approximately ",
            "≠" to " not equal to ", "≤" to " less than or equal to ", "≥" to " greater than or equal to ",
            "×" to " times ", "÷" to " divided by ", "±" to " plus or minus ", "√" to " square root of ",
            "∑" to " sum ", "∫" to " integral ", "→" to " goes to ", "⇒" to " implies ",
            "∀" to " for all ", "∃" to " there exists ", "∈" to " in ", "ℝ" to " real numbers ",
            "ℂ" to " complex numbers ", "ℤ" to " integers ", "ℕ" to " natural numbers ", "²" to " squared", "³" to " cubed"
        )
        for ((k, v) in unicodeMap) s = s.replace(k, v)

        // Strip remaining LaTeX commands & braces
        s = Regex("""\\[A-Za-z]+\*?""").replace(s, " ")
        s = s.replace("{", " ").replace("}", " ").replace("\\", " ")

        // Apply pronunciation glossary word-boundary replacements
        for ((term, spoken) in getGlossary()) {
            if (term.isNotBlank() && spoken.isNotBlank()) {
                s = Regex("""\b${Regex.escape(term)}\b""").replace(s, spoken)
            }
        }

        return s.replace(Regex("""\s+"""), " ").trim()
    }
}
