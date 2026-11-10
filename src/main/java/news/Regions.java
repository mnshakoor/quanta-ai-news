package news;

import java.util.*;

/**
 * Region vocabulary and event-region inference.
 *
 * Outlet region is declared in config/sources.tsv (where the outlet is based).
 * Event region is inferred per story from place names, demonyms and institutions in its
 * headlines and excerpts, using the gazetteer below. Matching is whole-word and
 * case-insensitive. A region must score at least 2 hits and lead the runner-up by 1.5x to
 * be assigned; otherwise a story naming several regions is "Global" and one naming none
 * is "Unspecified". The gazetteer is a lexicon, not entity resolution: it can mislabel
 * stories (for example a place name shared by two countries), and it is documented as such.
 */
final class Regions {
    private Regions() {}

    static final String NORTH_AMERICA = "North America";
    static final String LATIN_AMERICA = "Latin America";
    static final String EUROPE = "Europe";
    static final String EURASIA = "Russia & Eurasia";
    static final String MENA = "Middle East & North Africa";
    static final String SSA = "Sub-Saharan Africa";
    static final String SOUTH_ASIA = "South Asia";
    static final String EAST_ASIA = "East Asia & Pacific";
    static final String GLOBAL = "Global";
    static final String UNSPECIFIED = "Unspecified";

    static final List<String> ALL = List.of(NORTH_AMERICA, LATIN_AMERICA, EUROPE, EURASIA, MENA,
            SSA, SOUTH_ASIA, EAST_ASIA, GLOBAL);

    private static final Map<String, List<String>> GAZETTEER = new LinkedHashMap<>();
    static {
        GAZETTEER.put(NORTH_AMERICA, List.of(
            "united states", "u.s.", "america", "american", "americans", "washington", "white house", "congress",
            "senate", "pentagon", "trump", "biden", "republicans", "democrats", "gop", "supreme court",
            "fbi", "new york", "california", "texas", "florida", "chicago", "los angeles", "canada", "canadian",
            "ottawa", "toronto", "carney", "ontario", "quebec", "alaska", "hawaii"));
        GAZETTEER.put(LATIN_AMERICA, List.of(
            "mexico", "mexican", "brazil", "brazilian", "argentina", "argentine", "colombia", "colombian",
            "venezuela", "venezuelan", "chile", "chilean", "peru", "peruvian", "ecuador", "bolivia", "paraguay",
            "uruguay", "cuba", "cuban", "haiti", "haitian", "guatemala", "honduras", "el salvador", "nicaragua",
            "panama", "costa rica", "dominican republic", "puerto rico", "caribbean", "latin america",
            "lula", "milei", "sheinbaum", "maduro", "petro", "bogota", "caracas", "brasilia", "buenos aires",
            "sao paulo", "rio de janeiro", "lima", "santiago", "havana", "port-au-prince"));
        GAZETTEER.put(EUROPE, List.of(
            "europe", "european", "eu", "brussels", "nato", "britain", "british", "uk", "england", "scotland",
            "london", "starmer", "france", "french", "paris", "macron", "germany", "german", "berlin", "merz",
            "italy", "italian", "rome", "meloni", "spain", "spanish", "madrid", "portugal", "netherlands", "dutch",
            "belgium", "poland", "polish", "warsaw", "hungary", "hungarian", "orban", "romania", "bulgaria",
            "greece", "greek", "austria", "switzerland", "swiss", "sweden", "swedish", "norway", "norwegian",
            "finland", "finnish", "denmark", "danish", "ireland", "irish", "serbia", "kosovo", "bosnia",
            "croatia", "czech", "slovakia", "baltic", "estonia", "latvia", "lithuania", "vatican", "pope"));
        GAZETTEER.put(EURASIA, List.of(
            "russia", "russian", "russians", "moscow", "kremlin", "putin", "ukraine", "ukrainian", "ukrainians",
            "kyiv", "kiev", "zelensky", "zelenskyy", "donbas", "donetsk", "luhansk", "crimea", "kharkiv", "odesa",
            "zaporizhzhia", "belarus", "belarusian", "lukashenko", "minsk", "georgia's", "tbilisi", "armenia",
            "armenian", "azerbaijan", "azerbaijani", "baku", "kazakhstan", "uzbekistan", "kyrgyzstan",
            "tajikistan", "turkmenistan", "moldova", "central asia", "caucasus"));
        GAZETTEER.put(MENA, List.of(
            "israel", "israeli", "israelis", "gaza", "palestinian", "palestinians", "west bank", "hamas",
            "hezbollah", "netanyahu", "jerusalem", "tel aviv", "iran", "iranian", "tehran", "khamenei", "iraq",
            "iraqi", "baghdad", "syria", "syrian", "damascus", "lebanon", "lebanese", "beirut", "jordan",
            "jordanian", "saudi", "riyadh", "yemen", "yemeni", "houthi", "houthis", "sanaa", "aden", "oman",
            "qatar", "qatari", "doha", "uae", "emirates", "emirati", "dubai", "abu dhabi", "kuwait", "bahrain",
            "egypt", "egyptian", "cairo", "libya", "libyan", "tripoli", "tunisia", "algeria", "morocco",
            "moroccan", "turkey", "turkish", "turkiye", "ankara", "istanbul", "erdogan", "kurdish", "kurds",
            "red sea", "middle east", "gulf states", "persian gulf"));
        GAZETTEER.put(SSA, List.of(
            "africa", "african", "nigeria", "nigerian", "abuja", "lagos", "kenya", "kenyan", "nairobi",
            "ethiopia", "ethiopian", "addis ababa", "tigray", "amhara", "somalia", "somali", "mogadishu",
            "al-shabaab", "sudan", "sudanese", "khartoum", "darfur", "rsf", "south sudan", "south africa",
            "south african", "johannesburg", "pretoria", "cape town", "ramaphosa", "congo", "congolese", "drc",
            "kinshasa", "goma", "m23", "rwanda", "rwandan", "kigali", "uganda", "ugandan", "kampala", "tanzania",
            "ghana", "ghanaian", "senegal", "mali", "malian", "bamako", "burkina faso", "niger", "niamey",
            "chad", "cameroon", "mozambique", "zimbabwe", "zambia", "malawi", "angola", "namibia", "botswana",
            "madagascar", "eritrea", "djibouti", "sahel", "ecowas", "african union", "ivory coast",
            "cote d'ivoire", "liberia", "sierra leone", "guinea", "gabon", "burundi"));
        GAZETTEER.put(SOUTH_ASIA, List.of(
            "india", "indian", "indians", "new delhi", "delhi", "mumbai", "modi", "kashmir", "pakistan",
            "pakistani", "islamabad", "karachi", "lahore", "afghanistan", "afghan", "kabul", "taliban",
            "bangladesh", "bangladeshi", "dhaka", "sri lanka", "sri lankan", "colombo", "nepal", "nepali",
            "kathmandu", "bhutan", "maldives"));
        GAZETTEER.put(EAST_ASIA, List.of(
            "china", "chinese", "beijing", "xi jinping", "shanghai", "hong kong", "taiwan", "taiwanese",
            "taipei", "japan", "japanese", "tokyo", "south korea", "north korea", "korean", "seoul",
            "pyongyang", "kim jong un", "philippines", "philippine", "filipino", "manila", "vietnam",
            "vietnamese", "hanoi", "thailand", "thai", "bangkok", "cambodia", "myanmar", "burmese", "laos",
            "malaysia", "malaysian", "kuala lumpur", "indonesia", "indonesian", "jakarta", "singapore",
            "australia", "australian", "canberra", "sydney", "new zealand", "pacific islands", "papua",
            "fiji", "south china sea", "asean", "mongolia"));
    }

    /** Infers where a story's events take place from its articles' text. */
    static String eventRegion(List<Models.Article> arts) {
        StringBuilder sb = new StringBuilder(" ");
        for (Models.Article a : arts) sb.append(a.title()).append(" . ").append(a.description()).append(" . ");
        return eventRegion(sb.toString());
    }

    static String eventRegion(String raw) {
        String text = " " + raw.toLowerCase(Locale.ROOT).replace('’', '\'') + " ";
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : GAZETTEER.entrySet()) {
            int n = 0;
            for (String term : e.getValue()) n += count(text, term);
            if (n > 0) hits.put(e.getKey(), n);
        }
        if (hits.isEmpty()) return UNSPECIFIED;
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(hits.entrySet());
        ranked.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        int top = ranked.get(0).getValue();
        int second = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
        if (top >= 2 && top >= 1.5 * second) return ranked.get(0).getKey();
        if (ranked.size() > 1 && second >= 2) return GLOBAL;
        return top >= 1 && second == 0 ? ranked.get(0).getKey() : GLOBAL;
    }

    /** Whole-word occurrences of a term (terms may contain spaces, dots and apostrophes). */
    private static int count(String text, String term) {
        int n = 0, idx = 0;
        while ((idx = text.indexOf(term, idx)) >= 0) {
            int end = idx + term.length();
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            boolean endOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startOk && endOk) n++;
            idx = end;
        }
        return n;
    }
}
