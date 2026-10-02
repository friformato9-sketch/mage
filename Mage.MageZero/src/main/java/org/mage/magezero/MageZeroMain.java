package org.mage.magezero;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.cards.repository.RepositoryUtil;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class MageZeroMain {

    public static void main(String[] args) throws IOException {
        // Load config
        if (args.length > 0) {
            Config.load(args[0]);
        } else {
            Config.loadDefault();
        }
        // Initialize card database
        //RepositoryUtil.bootstrapLocalDb();
        //CardScanner.scan();
        Path lockPath = Paths.get("db", "magezero-bootstrap.lock");
        Files.createDirectories(lockPath.getParent());
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            RepositoryUtil.bootstrapLocalDb();
            CardScanner.scan();
        }

        if (Config.INSTANCE.format != null && !validateDecks()) {
            System.exit(2);
        }

        // Run training
        ParallelDataGenerator generator = new ParallelDataGenerator();
        generator.generateData();

        //CardRepository.instance.closeDB(true);
        System.exit(0);
    }

    /**
     * Checks both decks against the configured format and writes validation.json into the game log dir
     * (or the working directory), so callers can show the user exactly what is wrong with a decklist.
     */
    private static boolean validateDecks() throws IOException {
        JsonObject a = DeckCheck.check(Config.INSTANCE.playerA.deckPath, Config.INSTANCE.format);
        JsonObject b = DeckCheck.check(Config.INSTANCE.playerB.deckPath, Config.INSTANCE.format);
        boolean valid = a.get("valid").getAsBoolean() && b.get("valid").getAsBoolean();
        JsonObject root = new JsonObject();
        root.addProperty("format", Config.INSTANCE.format);
        root.addProperty("valid", valid);
        root.add("player_a", a);
        root.add("player_b", b);
        String dir = Config.INSTANCE.logging.gameLogDir == null ? "." : Config.INSTANCE.logging.gameLogDir;
        Files.createDirectories(Paths.get(dir));
        Files.write(Paths.get(dir, "validation.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            System.err.println("deck validation failed: " + root);
        }
        return valid;
    }
}