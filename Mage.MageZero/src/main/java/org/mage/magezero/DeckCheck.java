package org.mage.magezero;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.DeckValidator;
import mage.cards.decks.DeckValidatorError;
import mage.cards.decks.importer.DeckImporter;
import mage.deck.Modern;

/**
 * Loads a deck file the way the generator does and reports everything that would make a simulation
 * meaningless: unknown card names (the importer silently drops them) and format legality.
 */
public final class DeckCheck {

    private DeckCheck() {
    }

    public static DeckValidator validatorFor(String format) {
        if (format == null || format.isEmpty()) {
            return null;
        }
        switch (format.toLowerCase()) {
            case "modern":
                return new Modern();
            default:
                throw new IllegalArgumentException("unsupported format: " + format);
        }
    }

    /**
     * @return {"deck", "valid", "mainboard", "sideboard", "import_errors": [...], "format_errors": [{card, message}]}
     */
    public static JsonObject check(String deckPath, String format) {
        JsonObject result = new JsonObject();
        result.addProperty("deck", ParallelDataGenerator.extractDeckName(deckPath));
        JsonArray importErrors = new JsonArray();
        JsonArray formatErrors = new JsonArray();
        boolean valid;
        try {
            StringBuilder errors = new StringBuilder();
            DeckCardLists list = DeckImporter.importDeckFromFile(deckPath, errors, false);
            for (String line : errors.toString().split("\\R")) {
                if (!line.trim().isEmpty()) {
                    importErrors.add(line.trim());
                }
            }
            Deck deck = Deck.load(list, false, false);
            result.addProperty("mainboard", deck.getMaindeckCards().size());
            result.addProperty("sideboard", deck.getSideboard().size());
            valid = importErrors.isEmpty();
            DeckValidator validator = validatorFor(format);
            if (validator != null && !validator.validate(deck)) {
                valid = false;
                for (DeckValidatorError error : validator.getErrorsListSorted()) {
                    JsonObject e = new JsonObject();
                    e.addProperty("card", error.getCardName());
                    e.addProperty("message", error.getMessage());
                    formatErrors.add(e);
                }
            }
        } catch (Exception e) {
            importErrors.add(String.valueOf(e.getMessage()));
            valid = false;
        }
        result.addProperty("valid", valid);
        result.add("import_errors", importErrors);
        result.add("format_errors", formatErrors);
        return result;
    }
}
