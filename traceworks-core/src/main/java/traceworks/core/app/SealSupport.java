package traceworks.core.app;

import traceworks.core.port.EventLog;
import traceworks.core.port.SealStore;
import traceworks.core.port.Signer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the use cases need for seals: where the seals of the home live, and what signs for the operator.
 */
public record SealSupport(SealStore store, Signer signer) {

    public SealSupport {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(signer, "signer");
    }

    /**
     * A home that keeps no seals: an empty log that takes no line, no allowed signers and no signer. A package that
     * carries a seal is still judged, and refused, since nobody is allowed to have signed it.
     */
    public static SealSupport none() {
        EventLog empty = new EventLog() {
            @Override
            public String append(byte[] line) {
                throw new IllegalStateException("this home keeps no seal log");
            }

            @Override
            public List<byte[]> readAll() {
                return List.of();
            }

            @Override
            public byte[] tail() {
                return new byte[0];
            }

            @Override
            public byte[] discardTornTail() {
                return new byte[0];
            }
        };
        SealStore.Log log = new SealStore.Log() {
            @Override
            public EventLog events() {
                return empty;
            }

            @Override
            public void close() {
            }
        };
        SealStore store = new SealStore() {
            @Override
            public Log openLog() {
                return log;
            }

            @Override
            public Log openLogForWrite() {
                return log;
            }

            @Override
            public String logLocation() {
                return "(no seal log)";
            }

            @Override
            public Optional<byte[]> allowedSigners() {
                return Optional.empty();
            }

            @Override
            public String allowedSignersLocation() {
                return "(no allowed signers)";
            }

            @Override
            public void writeSeal(Path packageDirectory, byte[] content) {
                throw new IllegalStateException("this home keeps no seals");
            }

            @Override
            public boolean removeSeal(Path packageDirectory) {
                return false;
            }
        };
        return new SealSupport(store, (message, namespace, key) -> {
            throw new IllegalStateException("this home has no signer");
        });
    }
}
