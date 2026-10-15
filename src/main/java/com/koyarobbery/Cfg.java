package com.koyarobbery;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Loads config.json / loots.json / language.json (converted from the plugin's YAML) and data.json (regions, structures, builds). */
public final class Cfg {
    private Cfg() {}
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static JsonObject config, loots, language;
    public static Data data = new Data();
    private static Path dir;

    public static class Region {
        public String dim; public int x1, y1, z1, x2, y2, z2;
        public boolean contains(BlockPos p) {
            return p.getX() >= Math.min(x1, x2) && p.getX() <= Math.max(x1, x2)
                && p.getY() >= Math.min(y1, y2) && p.getY() <= Math.max(y1, y2)
                && p.getZ() >= Math.min(z1, z2) && p.getZ() <= Math.max(z1, z2);
        }
    }
    public static class Structure { public String region, type, dim, face; public int x, y, z; }
    public static class Build { public String region, dim, face; public int x, y, z; public List<Long> blocks = new ArrayList<>(); }
    public static class Data {
        public int id = 1;
        public Map<String, Region> regions = new LinkedHashMap<>();
        public Map<String, Structure> structures = new LinkedHashMap<>();
        public Map<String, Build> builds = new LinkedHashMap<>();
    }

    public static void load() {
        try {
            dir = FMLPaths.CONFIGDIR.get().resolve("koyarobbery");
            Files.createDirectories(dir);
            config = readJson("config.json");
            loots = readJson("loots.json");
            language = readJson("language.json");
            Path dp = dir.resolve("data.json");
            if (Files.exists(dp)) {
                try (Reader r = Files.newBufferedReader(dp, StandardCharsets.UTF_8)) {
                    Data d = GSON.fromJson(r, Data.class);
                    if (d != null) data = d;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("KoyaRobbery: failed to load config", e);
        }
    }

    private static JsonObject readJson(String name) throws IOException {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) {
            try (InputStream in = Cfg.class.getResourceAsStream("/defaults/" + name)) {
                if (in == null) throw new IOException("missing default " + name);
                Files.copy(in, p);
            }
        }
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    public static void saveData() {
        try (var w = Files.newBufferedWriter(dir.resolve("data.json"), StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        } catch (IOException e) { e.printStackTrace(); }
    }

    public static String dimOf(Level l) { return l.dimension().location().toString(); }

    /** Replaces WorldGuard's region lookup. */
    public static String regionAt(Level l, BlockPos p) {
        String d = dimOf(l);
        for (Map.Entry<String, Region> e : data.regions.entrySet())
            if (e.getValue().dim.equals(d) && e.getValue().contains(p)) return e.getKey();
        return null;
    }

    public static JsonObject places() { return config.getAsJsonObject("Places"); }

    public static String placeName(String input) {
        for (String k : places().keySet()) if (k.equalsIgnoreCase(input)) return k;
        return null;
    }

    public static JsonObject placeByRegion(String region) {
        for (Map.Entry<String, JsonElement> e : places().entrySet())
            if (e.getValue().getAsJsonObject().get("Region-Id").getAsString().equalsIgnoreCase(region))
                return e.getValue().getAsJsonObject();
        return null;
    }
}
