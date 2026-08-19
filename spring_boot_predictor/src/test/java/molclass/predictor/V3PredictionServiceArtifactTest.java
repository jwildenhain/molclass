package molclass.predictor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class V3PredictionServiceArtifactTest {

    @Test
    void streamsArtifactFromJdbcWithoutMaterializingPayload() throws Exception {
        byte[] payload = serializedArtifact("streamed model artifact");
        TrackingInputStream stream = new TrackingInputStream(payload);
        ResultSet row = mock(ResultSet.class);
        when(row.getBinaryStream(5)).thenReturn(stream);

        Object result = V3PredictionService.deserializeArtifact(
                row, payload.length, sha256(payload), payload.length, "MODEL");

        assertEquals("streamed model artifact", result);
        assertTrue(stream.closed);
        verify(row).getBinaryStream(5);
        verify(row, never()).getBytes(5);
    }

    @Test
    void rejectsHashMismatchAfterStreamingCompletePayload() throws Exception {
        byte[] payload = serializedArtifact("model");
        ResultSet row = rowFor(payload);
        byte[] wrongHash = sha256(payload);
        wrongHash[0] ^= 1;

        assertThrows(IllegalStateException.class, () -> V3PredictionService.deserializeArtifact(
                row, payload.length, wrongHash, payload.length, "MODEL"));
    }

    @Test
    void rejectsPayloadLongerThanDeclaredSize() throws Exception {
        byte[] payload = serializedArtifact("header");
        ResultSet row = rowFor(payload);
        int declaredSize = payload.length - 1;

        assertThrows(IllegalStateException.class, () -> V3PredictionService.deserializeArtifact(
                row, declaredSize, sha256(Arrays.copyOf(payload, declaredSize)), payload.length, "HEADER"));
    }

    @Test
    void rejectsPayloadShorterThanDeclaredSize() throws Exception {
        byte[] payload = serializedArtifact("header");
        ResultSet row = rowFor(payload);

        assertThrows(IllegalStateException.class, () -> V3PredictionService.deserializeArtifact(
                row, payload.length + 1L, sha256(payload), payload.length + 1L, "HEADER"));
    }

    @Test
    void rejectsOversizedDeclarationBeforeOpeningJdbcStream() throws Exception {
        byte[] payload = serializedArtifact("model");
        ResultSet row = mock(ResultSet.class);

        assertThrows(IllegalStateException.class, () -> V3PredictionService.deserializeArtifact(
                row, payload.length, sha256(payload), payload.length - 1L, "MODEL"));
        verify(row, never()).getBinaryStream(5);
    }

    private static ResultSet rowFor(byte[] payload) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getBinaryStream(5)).thenReturn(new TrackingInputStream(payload));
        return row;
    }

    private static byte[] serializedArtifact(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
             ObjectOutputStream output = new ObjectOutputStream(gzip)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static byte[] sha256(byte[] payload) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(payload);
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] payload) {
            super(payload);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
