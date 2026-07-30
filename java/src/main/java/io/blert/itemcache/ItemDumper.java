package io.blert.itemcache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.cache.ItemManager;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.fs.Store;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSRS item cache dumper.
 */
public class ItemDumper {
    private static final Logger log = LoggerFactory.getLogger(ItemDumper.class);

    private static final String DEFAULT_STATS_URL = "https://static.runelite.net/item/stats.ids.min.json";

    /** Bank note template ID. {@code ItemComposition.getNote() == 799} in RuneLite. */
    private static final int BANK_NOTE_TEMPLATE = 799;

    /**
     * Maps a RuneLite {@code EquipmentInventorySlot} index to Blert's proto
     * slot value.
     */
    private static Integer protoSlot(int inventorySlotIndex) {
        switch (inventorySlotIndex) {
            case 0: return 0;   // HEAD
            case 1: return 1;   // CAPE
            case 2: return 2;   // AMULET
            case 13: return 3;  // AMMO
            case 3: return 4;   // WEAPON
            case 4: return 5;   // TORSO (BODY)
            case 5: return 6;   // SHIELD
            case 7: return 7;   // LEGS
            case 9: return 8;   // GLOVES (HANDS)
            case 10: return 9;  // BOOTS
            case 12: return 10; // RING
            default: return null;
        }
    }

    public static void main(String[] args) throws IOException {
        Options options = new Options();
        options.addOption(Option.builder().longOpt("cachedir").hasArg().required()
                .desc("Directory containing an unpacked OSRS cache").build());
        options.addOption(Option.builder().longOpt("out-extended").hasArg().required()
                .desc("Path to write the extended items JSON").build());
        options.addOption(Option.builder().longOpt("out-simple").hasArg().required()
                .desc("Path to write the simple {id,name} items JSON").build());
        options.addOption(Option.builder().longOpt("stats-url").hasArg()
                .desc("URL of the RuneLite item stats service").build());

        CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(options, args);
        } catch (org.apache.commons.cli.ParseException ex) {
            System.err.println("Error parsing command line options: " + ex.getMessage());
            System.exit(2);
            return;
        }

        final Path cacheDir = Path.of(cmd.getOptionValue("cachedir"));
        final Path extendedOut = Path.of(cmd.getOptionValue("out-extended"));
        final Path simpleOut = Path.of(cmd.getOptionValue("out-simple"));
        final String statsUrl = cmd.getOptionValue("stats-url", DEFAULT_STATS_URL);

        log.info("Loading item stats from {}", statsUrl);
        Map<Integer, ServiceStats> statsById = loadStats(statsUrl);
        log.info("Loaded stats for {} items", statsById.size());

        log.info("Loading item definitions from cache at {}", cacheDir);
        List<Item> items;
        try (Store store = new Store(cacheDir.toFile())) {
            store.load();

            ItemManager itemManager = new ItemManager(store);
            itemManager.load();
            // Resolve noted/bought/placeholder items to their real definitions.
            itemManager.link();

            items = buildItems(itemManager, statsById);
        }

        log.info("Dumped {} items", items.size());

        // Extended output uses the default GSON configuration.
        writeJson(extendedOut, new Gson(), items);

        // The simple file historically came from a separate, manual step that
	// did not HTML escape, so apostrophes are emitted literally.
        List<SimpleItem> simple = new ArrayList<>(items.size());
        for (Item item : items) {
            simple.add(new SimpleItem(item.id, item.name));
        }
        writeJson(simpleOut, new GsonBuilder().disableHtmlEscaping().create(), simple);

        log.info("Wrote {} and {}", extendedOut, simpleOut);
    }

    private static List<Item> buildItems(ItemManager itemManager, Map<Integer, ServiceStats> statsById) {
        List<Integer> ids = new ArrayList<>();
        for (ItemDefinition def : itemManager.getItems()) {
            ids.add(def.id);
        }
        ids.sort(Integer::compareTo);

        List<Item> items = new ArrayList<>(ids.size());
        for (int id : ids) {
            ItemDefinition def = itemManager.getItem(id);
            if (def == null || def.name == null || def.name.isEmpty() || def.name.equals("null")) {
                continue;
            }
            if (def.placeholderTemplateId != -1) {
                continue;
            }

            Item item = new Item();
            item.id = id;
            item.name = def.name;
            item.tradeable = isTradeable(itemManager, def);
            item.stackable = def.stackable == 1;
            item.bankNote = def.notedTemplate == BANK_NOTE_TEMPLATE;

            if (def.notedTemplate == -1) {
                ServiceStats stats = statsById.get(id);
                if (stats != null && stats.equipment != null) {
                    ServiceEquipment eq = stats.equipment;
                    Stats s = new Stats();
                    s.stabAttack = eq.astab;
                    s.slashAttack = eq.aslash;
                    s.crushAttack = eq.acrush;
                    s.magicAttack = eq.amagic;
                    s.rangedAttack = eq.arange;
                    s.stabDefence = eq.dstab;
                    s.slashDefence = eq.dslash;
                    s.crushDefence = eq.dcrush;
                    s.magicDefence = eq.dmagic;
                    s.rangedDefence = eq.drange;
                    s.meleeStrength = eq.str;
                    s.rangedStrength = eq.rstr;
                    s.magicDamage = (int) eq.mdmg;
                    s.prayer = eq.prayer;
                    s.attackSpeed = eq.aspeed;
                    s.twoHanded = eq.isTwoHanded;

                    Integer slot = protoSlot(eq.slot);
                    if (slot != null) {
                        item.slot = slot;
                    } else {
                        log.info("Item {} unknown slot {}", def.name, eq.slot);
                    }

                    item.equipable = stats.equipable;
                    item.weight = stats.weight;
                    item.stats = s;
                }
            }

            items.add(item);
        }
        return items;
    }

    /**
     * Reproduces the live client's {@code ItemComposition.isTradeable()}.
     *
     * <p>The value is the item definition's opcode 15 {@code tradeable} flag,
     * but the game engine derives it through note and bought templates in a way
     * that {@code net.runelite.cache}'s {@link ItemManager#link()} does not
     * replicate:
     *
     * <ul>
     *   <li>Noted items inherit the flag from their unnoted base item.
     *   <li>"bought" placeholder items are never tradeable.
     * </ul>
     *
     * This rule was validated to reproduce the flag for all 21,476 dumped items
     * on cache build 238.
     */
    private static boolean isTradeable(ItemManager itemManager, ItemDefinition def) {
        if (def.notedTemplate != -1) {
            ItemDefinition base = itemManager.getItem(def.notedID);
            return base != null ? base.tradeable : def.tradeable;
        }
        if (def.boughtTemplateId != -1) {
            return false;
        }
        return def.tradeable;
    }

    private static Map<Integer, ServiceStats> loadStats(String statsUrl) throws IOException {
        Type type = new TypeToken<Map<Integer, ServiceStats>>() {}.getType();
        try (Reader reader = new InputStreamReader(new URL(statsUrl).openStream(), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, type);
        }
    }

    private static void writeJson(Path out, Gson gson, Object value) throws IOException {
        File parent = out.toAbsolutePath().getParent().toFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create output directory " + parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            gson.toJson(value, writer);
        }
    }

    private static final class Item {
        int id;
        String name;
        boolean tradeable;
        boolean equipable;
        boolean stackable;
        boolean bankNote;
        double weight;
        Integer slot;
        Stats stats;
    }

    private static final class Stats {
        int stabAttack;
        int slashAttack;
        int crushAttack;
        int magicAttack;
        int rangedAttack;
        int stabDefence;
        int slashDefence;
        int crushDefence;
        int magicDefence;
        int rangedDefence;
        int meleeStrength;
        int rangedStrength;
        int magicDamage;
        int prayer;
        int attackSpeed;
        boolean twoHanded;
    }

    private static final class SimpleItem {
        final int id;
        final String name;

        SimpleItem(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class ServiceStats {
        boolean equipable;
        double weight;
        @SerializedName("ge_limit")
        int geLimit;
        ServiceEquipment equipment;
    }

    private static final class ServiceEquipment {
        int slot;
        @SerializedName("is2h")
        boolean isTwoHanded;
        int astab;
        int aslash;
        int acrush;
        int amagic;
        int arange;
        int dstab;
        int dslash;
        int dcrush;
        int dmagic;
        int drange;
        int str;
        int rstr;
        float mdmg;
        int prayer;
        int aspeed;
    }
}
