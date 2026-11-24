package ua.zentix.incoming.gametest;

/**
 * The datapack examples of the guide's "For server owners and pack makers" page, as written there (both languages):
 * {@code PackDocsTest} checks the guide shows exactly these files, {@link PackExampleGameTests} loads each with a
 * vanilla block in place of the example mod's block and checks the effect the guide claims.
 */
public final class PackExamples {
    public static final String CONCRETE = "examplemod:reinforced_concrete";
    public static final String SHEET_METAL = "examplemod:sheet_metal_wall";
    public static final String STRAW = "examplemod:straw_bale";

    /** {@code data/incoming/data_maps/block/penetration.json}: a mod's reinforced concrete, a full metre per block. */
    public static final String PENETRATION = """
            {
              "values": {
                "examplemod:reinforced_concrete": {
                  "kind": "concrete",
                  "s": 0.84,
                  "breach": "reinforced",
                  "thickness": 1.0
                }
              }
            }
            """;

    /** {@code data/incoming/tags/block/nuke_light.json}: a mod's sheet-metal wall breaks like a light structure. */
    public static final String BLOCK_CLASS = """
            {
              "values": [
                { "id": "examplemod:sheet_metal_wall", "required": false }
              ]
            }
            """;

    /** {@code data/incoming/tags/block/pulse_dry.json}: a mod's straw bale is dry fuel. */
    public static final String IGNITION = """
            {
              "values": [
                { "id": "examplemod:straw_bale", "required": false }
              ]
            }
            """;

    private PackExamples() {}
}
