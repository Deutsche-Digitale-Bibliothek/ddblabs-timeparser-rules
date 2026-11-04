package de.deutsche_digitale_bibliothek.timeparser.web;

import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * Bindet die Bestätigung eines ungültigen Formulars an den angezeigten Prüfstand.
 *
 * <p>Signiert werden alle gespeicherten Formularwerte, ihre IDs und Reihenfolgen
 * sowie die aktuellen Fehler mit Feld, Code und Meldung. Zustimmungsfelder gehören
 * nicht zum Inhalt. Null-Zeichenketten entsprechen leeren HTML-Feldwerten; eine
 * fehlende Gruppen-ID bleibt dagegen von jeder gespeicherten ID unterscheidbar.</p>
 *
 * <p>Der zufällige Schlüssel gehört nur zu dieser Instanz. Ein Neustart macht
 * bisherige Bestätigungen ungültig; Token und Schlüssel werden nicht persistiert.
 * Der Token ersetzt weder die erneute Validierung noch die ausdrücklich vom
 * Controller zu prüfende Zustimmung zum Speichern.</p>
 */
final class InvalidRuleSaveReview {

    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec signingKey;

    InvalidRuleSaveReview() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        signingKey = new SecretKeySpec(secret, ALGORITHM);
    }

    /**
     * Erstellt den Bestätigungstoken für den aktuell dargestellten Formularstand.
     *
     * @param form geprüfter Entwurf, einschließlich seiner maßgeblichen Gruppen-ID
     * @param errors aktuelle Bindungs- und fachliche Fehler; deren Reihenfolge ist unerheblich
     * @return hexadezimale Signatur; enthält keine lesbaren Formulardaten
     */
    String issue(RuleGroupForm form, Errors errors) {
        return HexFormat.of().formatHex(signature(form, errors));
    }

    /**
     * Prüft einen zuvor ausgegebenen Token gegen die erneut validierten Werte.
     *
     * @param token übermittelte Bestätigung; fehlende und fehlerhafte Werte werden abgewiesen
     * @param form aktueller Formularinhalt
     * @param errors neu ermittelte Fehler dieses Inhalts
     * @return ob Inhalt und Fehler unverändert mit der Signatur übereinstimmen
     */
    boolean matches(String token, RuleGroupForm form, Errors errors) {
        if (token == null || token.length() != 64) {
            return false;
        }
        final byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(token);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(supplied, signature(form, errors));
    }

    private byte[] signature(RuleGroupForm form, Errors errors) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(signingKey);
            add(mac, "invalid-rule-save-review-v1");
            add(mac, form.getId() == null ? "" : form.getId().toString());
            add(mac, form.getName());
            add(mac, form.getDescription());
            add(mac, Integer.toString(form.getRules().size()));
            for (var rule : form.getRules()) {
                add(mac, rule.getId());
                add(mac, rule.getInputMask());
                add(mac, rule.getInputPattern());
                add(mac, rule.getOutputMask());
                add(mac, rule.getOutputPattern());
                add(mac, Integer.toString(rule.getTests().size()));
                for (var test : rule.getTests()) {
                    add(mac, test.getId());
                    add(mac, test.getInput());
                    add(mac, test.getTokenized());
                    add(mac, test.getOutput());
                    add(mac, test.getTimespan());
                }
            }
            var signatures = errors.getAllErrors().stream().map(ErrorSignature::of)
                    .sorted(Comparator.comparing(ErrorSignature::objectName)
                            .thenComparing(ErrorSignature::location)
                            .thenComparing(ErrorSignature::code)
                            .thenComparing(ErrorSignature::message))
                    .toList();
            add(mac, Integer.toString(signatures.size()));
            for (ErrorSignature error : signatures) {
                add(mac, error.objectName());
                add(mac, error.location());
                add(mac, error.code());
                add(mac, error.message());
            }
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Der Prüfstand konnte nicht signiert werden.", exception);
        }
    }

    /** Längenpräfixe verhindern Kollisionen durch Feldtrenner in frei eingegebenem Text. */
    private static void add(Mac mac, String value) {
        byte[] bytes = normalized(value).getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
    }

    private static String normalized(String value) {
        return value == null ? "" : value;
    }

    /** Rejected Values und lokalisierungsabhängige Binding-Darstellungen gehören nicht zur Signatur. */
    private record ErrorSignature(String objectName, String location, String code, String message) {
        private static ErrorSignature of(ObjectError error) {
            return new ErrorSignature(normalized(error.getObjectName()),
                    error instanceof FieldError fieldError ? "field:" + fieldError.getField() : "object",
                    normalized(error.getCode()), normalized(error.getDefaultMessage()));
        }
    }
}
