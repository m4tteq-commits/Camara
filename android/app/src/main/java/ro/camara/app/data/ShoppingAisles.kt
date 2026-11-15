package ro.camara.app.data

internal enum class ShoppingAisle(val id: String, val label: String, private vararg val terms: String) {
    Produce("produce", "Fructe și legume", "rosii", "rosie", "cartof", "cartofi", "ceapa", "usturoi", "morcov",
        "morcovi", "varza", "salata", "ardei", "castraveti", "mar", "mere", "banane", "lamaie", "lamai",
        "portocale", "pere", "ciuperci", "dovlecel", "avocado", "spanac", "broccoli"),
    Dairy("dairy", "Lactate și ouă", "lapte", "iaurt", "branza", "telemea", "cascaval", "unt", "smantana", "ou", "oua",
        "kefir", "mozzarella", "parmezan", "ricotta", "feta"),
    Meat("meat", "Carne și pește", "pui", "curcan", "porc", "vita", "somon", "pastrav", "carne", "peste",
        "creveti", "sunca", "salam", "bacon"),
    Bakery("bakery", "Panificație", "paine", "chifle", "bagheta", "covrigi", "lipii", "croissant"),
    Pantry("pantry", "Alimente de bază", "orez", "paste", "faina", "ulei", "zahar", "sare", "piper",
        "conserva", "conserve", "linte", "naut", "fasole uscata", "ovaz", "otet", "pesmet", "drojdie", "malai"),
    Frozen("frozen", "Congelate", "congelat", "congelata", "congelate", "congelati", "inghetata"),
    Drinks("drinks", "Băuturi", "apa", "suc", "ceai", "cafea", "bere", "vin"),
    Other("other", "Altele");

    private fun matches(name: String) = terms.any { name.contains(" $it ") }

    companion object {
        fun forProduct(name: String, override: String = ""): ShoppingAisle {
            entries.firstOrNull { it.id == override }?.let { return it }
            val normalized = " ${RecipeMath.normalize(name).replace(Regex("[^a-z0-9]+"), " ").trim()} "
            if (Frozen.matches(normalized)) return Frozen
            if (Pantry.matches(normalized)) return Pantry
            return entries.firstOrNull { it.matches(normalized) } ?: Other
        }

        fun ordered(saved: String?): List<ShoppingAisle> {
            val savedIds = saved.orEmpty().split(',').distinct()
            val rank = savedIds.withIndex().associate { it.value to it.index }
            return entries.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        }
    }
}
