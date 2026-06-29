package io.github.thelok1s.orchestra;

/** Android-free hex helpers shared by the RFCOMM and AAP engines (so AAP framing stays
 *  unit-testable on the plain JVM). Semantics match the original RfcommEngine implementation. */
final class HexUtil {
    private HexUtil() {}

    static String hex(byte[] a) {
        StringBuilder sb = new StringBuilder();
        for (byte b : a) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    static byte[] unhex(String s) {
        s = s.replaceAll("[^0-9a-fA-F]", "");
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }
}
