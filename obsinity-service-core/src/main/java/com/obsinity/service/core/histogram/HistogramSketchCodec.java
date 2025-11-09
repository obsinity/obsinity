package com.obsinity.service.core.histogram;

import com.datadoghq.sketch.ddsketch.DDSketch;
import com.datadoghq.sketch.ddsketch.encoding.ByteArrayInput;
import com.datadoghq.sketch.ddsketch.encoding.GrowingByteArrayOutput;
import com.datadoghq.sketch.ddsketch.store.Store;
import com.datadoghq.sketch.ddsketch.store.UnboundedSizeDenseStore;
import java.io.IOException;
import java.util.function.Supplier;

/** Serialization helpers for persisting DDSketch payloads. */
public final class HistogramSketchCodec {

    private static final Supplier<Store> STORE_SUPPLIER = UnboundedSizeDenseStore::new;

    private HistogramSketchCodec() {}

    public static byte[] serialize(DDSketch sketch) {
        if (sketch == null) {
            return new byte[0];
        }
        try {
            GrowingByteArrayOutput output = GrowingByteArrayOutput.withDefaultInitialCapacity();
            sketch.encode(output, false); // include mapping info
            return output.trimmedCopy();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize histogram sketch", ex);
        }
    }

    public static DDSketch deserialize(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            ByteArrayInput input = ByteArrayInput.wrap(payload);
            return DDSketch.decode(input, STORE_SUPPLIER);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to deserialize histogram sketch", ex);
        }
    }
}
