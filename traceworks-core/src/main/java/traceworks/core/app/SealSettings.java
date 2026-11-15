package traceworks.core.app;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The operator's settings for seals ({@code traceworks.json}).
 *
 * @param require        which runs need a sealed package ({@code require_seal})
 * @param immutable      whether {@code traceworks unseal} is switched off ({@code seal_immutable})
 * @param signingKey     the key {@code traceworks seal} signs with ({@code signing_key}); {@code null} for none
 * @param allowedSigners the allowed signers file ({@code allowed_signers}); {@code null} for
 *                       {@code allowed_signers} in the home
 */
public record SealSettings(Require require, boolean immutable, Path signingKey, Path allowedSigners) {

    /** No run needs a seal, seals can be removed, no signing key, the allowed signers file of the home. */
    public static final SealSettings DEFAULTS = new SealSettings(Require.NEVER, false, null, null);

    /** Which runs need a sealed package. */
    public enum Require {
        /** None: an unsealed package runs. */
        NEVER,
        /** Every run without a terminal, unless the command line carries the operator's flag. */
        UNATTENDED,
        /** Every run. */
        ALWAYS;

        /** As {@code traceworks.json} writes it: lower case. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The value of {@code key}; {@code null} when it names none. */
        public static Require of(String key) {
            for (Require require : values()) {
                if (require.key().equals(key)) {
                    return require;
                }
            }
            return null;
        }
    }

    public SealSettings {
        require = require == null ? Require.NEVER : require;
    }
}
