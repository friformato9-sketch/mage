package org.mage.magezero.scenario;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.cards.repository.CardScanner;
import mage.cards.repository.RepositoryUtil;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.mage.test.serverside.base.MageTestPlayerBase;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Usage: ScenarioMain &lt;scenario.json&gt; &lt;out-dir&gt;
 * <p>
 * Writes &lt;out-dir&gt;/result.json (see {@link ScenarioRunner}) and &lt;out-dir&gt;/log.jsonl.
 * Exit code 0 when result.json was written (check its "ok" flag), 1 on bad usage or a crash.
 * Must run from the XMage distribution folder (needs config/config.xml and the card db).
 */
public class ScenarioMain {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: ScenarioMain <scenario.json> <out-dir>");
            System.exit(1);
        }
        Path out = Paths.get(args[1]);
        try {
            Files.createDirectories(out);
            JsonObject spec = ScenarioRunner.readSpec(Paths.get(args[0]));

            Path lockPath = Paths.get("db", "magezero-bootstrap.lock");
            Files.createDirectories(lockPath.getParent());
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                RepositoryUtil.bootstrapLocalDb();
                CardScanner.scan();
            }
            MageTestPlayerBase.init();
            Logger.getRootLogger().setLevel(Level.WARN); // init() turns on DEBUG for the whole engine

            // libraries only pad the game (draws, deck-size checks): plain Wastes never interact
            Path filler = out.resolve("filler.txt");
            Files.write(filler, ("40 " + ScenarioRunner.FILLER_CARD + "\n").getBytes(StandardCharsets.UTF_8));

            JsonObject result = new ScenarioRunner(filler.toAbsolutePath().toString()).run(spec, out);
            write(out, result);
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            JsonObject result = new JsonObject();
            result.addProperty("ok", false);
            JsonArray errors = new JsonArray();
            errors.add("scenario crashed: " + t);
            result.add("errors", errors);
            try {
                write(out, result);
            } catch (Exception ignored) {
            }
            System.exit(1);
        }
    }

    private static void write(Path out, JsonObject result) throws Exception {
        Files.write(out.resolve("result.json"),
                new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()
                        .toJson(result).getBytes(StandardCharsets.UTF_8));
    }
}
