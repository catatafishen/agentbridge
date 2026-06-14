// Fixture symbols with stable names, used by the IDE integration bench to assert
// PSI-dependent MCP tools against a real Rider (RD) / ReSharper backend. Do not
// rename casually — integration tests reference 'Widget' and 'Point' by name.

namespace Fixture
{
    public class Widget
    {
        private int _width;
        private int _height;

        public int Area()
        {
            return _width * _height;
        }
    }

    public struct Point
    {
        public int X;
        public int Y;
    }
}
