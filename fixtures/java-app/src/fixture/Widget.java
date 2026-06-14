// Fixture symbols with stable names, used by the IDE integration bench to assert
// PSI-dependent MCP tools against a real IntelliJ IDEA (IU) backend. Do not rename
// casually — integration tests reference 'Widget' and 'Point' by name.

package fixture;

public class Widget {
    private int width;
    private int height;

    public int area() {
        return width * height;
    }
}
