package news;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Loads the outlet registry from config/sources.tsv. */
final class SourceRegistry {
    private static final Set<String> BIASES = Set.of("Left", "Lean Left", "Center", "Lean Right", "Right");
    private final Map<String, Models.Source> byId = new LinkedHashMap<>();
    private final Map<String, Models.Source> byName = new HashMap<>();

    SourceRegistry(Path file) throws IOException {
        int lineNo = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 9) {
                System.err.println("[registry] skipping line " + lineNo + ": expected 9 tab-separated columns");
                continue;
            }
            String bias = f[2].trim();
            if (!BIASES.contains(bias)) {
                System.err.println("[registry] skipping line " + lineNo + ": unknown bias '" + bias + "'");
                continue;
            }
            Models.Source s = new Models.Source(f[0].trim(), f[1].trim(), bias, f[3].trim(),
                    f[4].trim(), f[5].trim(), f[6].trim(), f[7].trim(), f[8].trim());
            byId.put(s.id(), s);
            byName.put(s.name().toLowerCase(Locale.ROOT), s);
        }
    }

    Collection<Models.Source> all() { return byId.values(); }

    Models.Source byName(String name) { return byName.get(name.toLowerCase(Locale.ROOT)); }

    Models.Source byId(String id) { return byId.get(id); }
}
