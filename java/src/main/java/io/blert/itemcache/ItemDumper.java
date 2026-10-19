package io.blert.itemcache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import net.runelite.cache.IndexType;
import net.runelite.cache.ItemManager;
import net.runelite.cache.SpriteManager;
import net.runelite.cache.TextureManager;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.providers.ModelProvider;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.item.ItemSpriteFactory;
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
        options.addOption(Option.builder().longOpt("out-sprites").hasArg()
                .desc("Directory to write item sprite PNGs and their manifest").build());
        options.addOption(Option.builder().longOpt("out-item-sprites").hasArg()
                .desc("Path to write the item sprite metadata JSON (requires --out-sprites)").build());

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
        final Path spritesOut = cmd.hasOption("out-sprites") ? Path.of(cmd.getOptionValue("out-sprites")) : null;
        final Path itemSpritesOut =
                cmd.hasOption("out-item-sprites") ? Path.of(cmd.getOptionValue("out-item-sprites")) : null;
        if (itemSpritesOut != null && spritesOut == null) {
            System.err.println("--out-item-sprites requires --out-sprites");
            System.exit(2);
            return;
        }

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

            if (spritesOut != null) {
                renderSprites(store, itemManager, items, spritesOut, itemSpritesOut);
            }
        }

        log.info("Dumped {} items", items.size());

        // Print one item per line for readable diffs. Escaping off for literal names.
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        writeItemsPerLine(extendedOut, gson, items);

        List<SimpleItem> simple = new ArrayList<>(items.size());
        for (Item item : items) {
            simple.add(new SimpleItem(item.id, item.name));
        }
        writeItemsPerLine(simpleOut, gson, simple);

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

    private static int[] stackThresholds(ItemDefinition def) {
        if (def.countCo == null) {
            return null;
        }
        TreeSet<Integer> thresholds = new TreeSet<>();
        for (int count : def.countCo) {
            if (count > 1) {
                thresholds.add(count);
            }
        }
        return thresholds.isEmpty() ? null : thresholds.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Renders each item's sprite to {@code <id>.png}, and its sprite at each
     * stack threshold to {@code <id>_<threshold>.png}, centering the item
     * within the sprite.
     * Writes a manifest mapping each file to a SHA-256 hash of its pixels.
     * If {@code itemSpritesOut} is set, outputs metadata about each item's
     * sprites.
     */
    private static void renderSprites(Store store, ItemManager itemManager, List<Item> items, Path outDir,
            Path itemSpritesOut) throws IOException {
        Files.createDirectories(outDir);

        SpriteManager spriteManager = new SpriteManager(store);
        spriteManager.load();
        TextureManager textureManager = new TextureManager(store);
        textureManager.load();

        Index models = store.getIndex(IndexType.MODELS);
        ModelProvider modelProvider = modelId -> {
            Archive archive = models.getArchive(modelId);
            if (archive == null) {
                return null;
            }
            byte[] data;
            // Store reads are not thread-safe.
            synchronized (store) {
                data = archive.decompress(store.getStorage().loadArchive(archive));
            }
            return new ModelLoader().load(modelId, data);
        };

        Map<Integer, ItemSprite> itemSprites = new LinkedHashMap<>();
        // Each render allocates large scratch buffers, so memory use grows with the thread count.
        int threads = Math.min(Runtime.getRuntime().availableProcessors(), 4);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Map<String, Future<String>> pending = new LinkedHashMap<>();
        try {
            for (Item item : items) {
                List<Integer> quantities = new ArrayList<>();
                quantities.add(1);
                int[] thresholds = stackThresholds(itemManager.getItem(item.id));
                if (thresholds != null) {
                    itemSprites.put(item.id, new ItemSprite(thresholds));
                    for (int threshold : thresholds) {
                        quantities.add(threshold);
                    }
                }
                for (int quantity : quantities) {
                    String file = quantity == 1 ? item.id + ".png" : item.id + "_" + quantity + ".png";
                    pending.put(file, pool.submit(() -> {
                        BufferedImage sprite = ItemSpriteFactory.createSprite(itemManager, modelProvider,
                                spriteManager, textureManager, item.id, quantity, 1, 0, false);
                        if (sprite == null) {
                            return null;
                        }
                        BufferedImage image = center(sprite);
                        ImageIO.write(image, "png", outDir.resolve(file).toFile());
                        return pixelHash(image);
                    }));
                }
            }

            Map<String, String> manifest = new LinkedHashMap<>();
            for (Map.Entry<String, Future<String>> entry : pending.entrySet()) {
                String hash = entry.getValue().get();
                if (hash == null) {
                    log.warn("No sprite rendered for {}", entry.getKey());
                    continue;
                }
                manifest.put(entry.getKey(), hash);
            }
            Gson gson = new Gson();
            writeEntriesPerLine(outDir.resolve("manifest.json"), gson, manifest);
            log.info("Rendered {} sprites to {}", manifest.size(), outDir);

            if (itemSpritesOut != null) {
                writeEntriesPerLine(itemSpritesOut, gson, itemSprites);
                log.info("Wrote {}", itemSpritesOut);
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to render item sprites", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Returns a copy of an image with its non-transparent pixels centered. */
    private static BufferedImage center(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return image;
        }

        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        int[] pixels = image.getRGB(minX, minY, w, h, null, 0, w);
        BufferedImage centered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        centered.setRGB((width - w) / 2, (height - h) / 2, w, h, pixels, 0, w);
        return centered;
    }

    /** Returns a SHA-256 hash of an image's dimensions and ARGB pixels. */
    private static String pixelHash(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        ByteBuffer buf = ByteBuffer.allocate(8 + pixels.length * 4);
        buf.putInt(width).putInt(height);
        for (int pixel : pixels) {
            buf.putInt(pixel);
        }

        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(buf.array());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void writeEntriesPerLine(Path out, Gson gson, Map<?, ?> entries) throws IOException {
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            writer.write("{\n");
            int i = 0;
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                writer.write("  ");
                writer.write(gson.toJson(entry.getKey().toString()));
                writer.write(": ");
                writer.write(gson.toJson(entry.getValue()));
                writer.write(++i < entries.size() ? ",\n" : "\n");
            }
            writer.write("}\n");
        }
    }

    private static Map<Integer, ServiceStats> loadStats(String statsUrl) throws IOException {
        Type type = new TypeToken<Map<Integer, ServiceStats>>() {}.getType();
        try (Reader reader = new InputStreamReader(new URL(statsUrl).openStream(), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, type);
        }
    }

    private static void writeItemsPerLine(Path out, Gson gson, List<?> items) throws IOException {
        File parent = out.toAbsolutePath().getParent().toFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create output directory " + parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            if (items.isEmpty()) {
                writer.write("[]\n");
                return;
            }
            writer.write("[\n");
            for (int i = 0; i < items.size(); i++) {
                writer.write("  ");
                writer.write(gson.toJson(items.get(i)));
                writer.write(i < items.size() - 1 ? ",\n" : "\n");
            }
            writer.write("]\n");
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

    private static final class ItemSprite {
        final int[] stackThresholds;

        ItemSprite(int[] stackThresholds) {
            this.stackThresholds = stackThresholds;
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
