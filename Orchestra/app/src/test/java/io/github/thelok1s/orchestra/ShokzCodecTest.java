package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Verifies the shokz_v1 wiring: a channel whose {@code protocol.framing = "shokz_v1"} routes to the
 * Shokz engine (not the Soundcore RFCOMM engine) even though its transport is "rfcomm", the verbatim
 * frames parse, and the slider value-patch reproduces the captured max-volume frame byte-for-byte
 * (modulo the device-ignored checksum).
 */
public class ShokzCodecTest {

    // Captured live on OpenSwim Pro. vol_0 = template; vol_16 = ground truth for value=16 (0x10).
    private static final String VOL_0  =
        "53ef71a55a3000010000002c00ec0a010000002400000002000000040000000100000003000000040000000a00000004000000040000000000000031";
    private static final String VOL_16 =
        "53ef71a55a3000010000002c00e8ca010000002400000002000000040000000100000003000000040000000a00000004000000040000001000000031";

    private static JSONObject shokzManifest() throws Exception {
        JSONObject proto = new JSONObject().put("framing", "shokz_v1");
        JSONObject ch = new JSONObject()
            .put("transport", "rfcomm")
            .put("uuid", "00001101-0000-1000-8000-00805f9b34fb")
            .put("secure", false)
            .put("protocol", proto);
        JSONObject channels = new JSONObject().put("rfcomm-control", ch);

        JSONObject eqSet = new JSONObject()
            .put("frames", new JSONObject()
                .put("standard", "53ff7101a55a3000")   // shortened stand-ins are fine for routing/parse
                .put("vocal", "53ff7102a55a3000"));
        JSONObject eq = new JSONObject()
            .put("id", "eq_preset").put("type", "multitoggle").put("title", "EQ")
            .put("options", new JSONArray()
                .put(new JSONObject().put("id", "standard").put("label", "Standard"))
                .put(new JSONObject().put("id", "vocal").put("label", "Vocal")))
            .put("set", eqSet)
            .put("inject", "auto")
            .put("ui", new JSONObject().put("setting_id", 3001))
            .put("_verified", true);

        JSONObject volSet = new JSONObject()
            .put("frame_template", VOL_0).put("value_offset", 55).put("value_size", 4);
        JSONObject vol = new JSONObject()
            .put("id", "mp3_volume").put("type", "slider").put("title", "MP3 volume")
            .put("range", new JSONObject().put("min", 0).put("max", 16).put("step", 1))
            .put("set", volSet)
            .put("inject", false)
            .put("ui", new JSONObject().put("setting_id", 3014))
            .put("_verified", true);

        return new JSONObject()
            .put("schema_version", 3).put("revision", 2).put("id", "shokz-openswim-pro")
            .put("name", "Shokz OpenSwim Pro")
            .put("channels", channels).put("default_channel", "rfcomm-control")
            .put("functions", new JSONArray().put(eq).put(vol));
    }

    @Test public void framingRoutesToShokzEngineNotSoundcore() throws Exception {
        DeviceDef def = DeviceDef.parse(shokzManifest());
        assertNotNull(def);
        DeviceDef.Func eq = def.funcById("eq_preset");
        assertNotNull(eq);
        assertEquals("rfcomm", eq.transport);        // physical transport is still SPP/RFCOMM
        assertEquals("shokz_v1", eq.framing);          // ...but framing selects the codec
        assertSame("must not fall through to the Soundcore RFCOMM engine",
                ControlEngine.SHOKZ, ControlEngine.forFunc(eq));
    }

    @Test public void verbatimFramesParse() throws Exception {
        DeviceDef def = DeviceDef.parse(shokzManifest());
        DeviceDef.Func eq = def.funcById("eq_preset");
        assertEquals(2, eq.frames.size());
        assertTrue(eq.frames.containsKey("standard"));
        assertTrue(eq.frames.containsKey("vocal"));
    }

    @Test public void sliderValuePatchReproducesCapturedMaxFrame() throws Exception {
        DeviceDef def = DeviceDef.parse(shokzManifest());
        DeviceDef.Func vol = def.funcById("mp3_volume");
        assertEquals(55, vol.frameValueOffset);

        byte[] got = ShokzEngine.levelFrame(vol, 16);
        assertNotNull(got);

        // Expected = captured vol_16 with the device-ignored checksum (FORMAT B, bytes 13-14) zeroed.
        byte[] want = HexUtil.unhex(VOL_16);
        want[13] = 0; want[14] = 0;
        assertArrayEquals("patched value=16 frame must match the captured max-volume frame", want, got);
    }

    @Test public void sliderIntermediateStepPatchesValueLE() throws Exception {
        DeviceDef def = DeviceDef.parse(shokzManifest());
        DeviceDef.Func vol = def.funcById("mp3_volume");
        byte[] got = ShokzEngine.levelFrame(vol, 9);
        // value u32-LE at offset 55
        assertEquals(9, got[55] & 0xff);
        assertEquals(0, got[56] & 0xff);
        assertEquals(0x31, got[got.length - 1] & 0xff); // FORMAT B tail intact
    }

    @Test public void unknownFramingHasNoEngine() throws Exception {
        DeviceDef.Func f = DeviceDef.parseFuncForTest(new JSONObject()
            .put("id", "x").put("type", "toggle").put("title", "X"));
        // No channels -> transport/framing null -> no engine.
        assertNull(ControlEngine.forFunc(f));
    }
}
