package io.github.drtron1c.cloudphotos.photos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Base64;
import java.util.Map;

import org.junit.Test;

/** Samples produced by Python's plistlib in binary format (same layout as iCloud's locationEnc). */
public class BPlistTest {
    private static final String SAMPLE =
            "YnBsaXN0MDDZAQIDBAUGBwgJCgsMDQ4PCxARU2FsdFZjb3Vyc2VXaG9yekFjY1NsYXRTbG9uVG5hbWVVc3BlZWRZdGltZXN0YW1wV3ZlcnRBY2MjQCkAAAAAAAAjv/AAAAAAAAAjQBQAAAAAAAAjQEL97XxvvScjQDe6P/7zkIZoA5EDugPBA8wDwAO/A7sDtzNBxvY8IAAAACNACAAAAAAAAAgbHyYuMjY7QUtTXGVud4CRmgAAAAAAAAEBAAAAAAAAABIAAAAAAAAAAAAAAAAAAACj";

    @Test
    public void readsLatLonAndStrings() {
        byte[] raw = Base64.getDecoder().decode(SAMPLE);
        double[] ll = BPlist.latLon(raw);
        assertEquals(37.983810, ll[0], 1e-9);
        assertEquals(23.727539, ll[1], 1e-9);
        Map<?, ?> m = (Map<?, ?>) BPlist.parse(raw);
        assertEquals("Ακρόπολη", m.get("name"));
        assertEquals(12.5, ((Number) m.get("alt")).doubleValue(), 1e-9);
    }

    @Test
    public void rejectsGarbage() {
        assertNull(BPlist.latLon(new byte[]{1, 2, 3}));
        assertNull(BPlist.latLon("this is not a plist at all, definitely not one".getBytes()));
    }
}
